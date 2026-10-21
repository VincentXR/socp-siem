<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/loading/style/css.mjs'
import { vLoading } from 'element-plus/es/components/loading/index.mjs'
const route = useRoute()
const router = useRouter()

import ActionFeedback from '../components/ActionFeedback.vue'
import FormField from '../components/FormField.vue'
import FormGrid from '../components/FormGrid.vue'
import FormSection from '../components/FormSection.vue'
import PagerBar from '../components/PagerBar.vue'
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
import 'element-plus/es/components/message/style/css.mjs'
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { getSource, SOURCE_TYPES, PARSE_FORMATS, type LogSource, type SinkTarget, type IngestParseFailure } from '../api'
import { useI18n } from '../composables/useI18n'
import { fmtBytes, fmtTime } from '../lib/ui'
import PageHeader from '../components/PageHeader.vue'
import IngestSourceSetup from '../components/IngestSourceSetup.vue'
import { useIngestCopy } from '../composables/useIngestCopy'

import { useIngestWorkspace } from '../composables/ingest/useIngestWorkspace'
import { useIngestSources } from '../composables/ingest/useIngestSources'
import { useIngestOutputs } from '../composables/ingest/useIngestOutputs'
import { useIngestRules } from '../composables/ingest/useIngestRules'
import { useIngestTasks } from '../composables/ingest/useIngestTasks'
import { useIngestQuarantine } from '../composables/ingest/useIngestQuarantine'

const { t } = useI18n()
const copy = useIngestCopy()
const workspace = useIngestWorkspace()
const { canWrite, actionBusy, actionError, confirming, loadErrors, loadError } = workspace
const setupId = ref('')
const setupSample = ref('')
const showSetup = ref(false)
let setupEditGeneration = 0
let setupEditController: AbortController | null = null
const MULTILINE_PLACEHOLDER = '{ start_pattern = "^\\s", mode = "continue_through" }'
const validTabs = ['tasks', 'sources', 'rules', 'outputs', 'quarantine']
const ingestTab = ref(validTabs.includes(String(route.query.tab)) ? String(route.query.tab) : 'tasks')
const {
  sources, sourcesLoading, sourcePage, sourceSize, sourceTotal, sourceSearchDraft, loadSources, applySourceSearch,
  logCategories, loadCategories, sourceRuleOptions, sourceRuleTotal, sourceRuleSelectionVersion, sourceRuleLoading,
  searchSourceRules, sourceRuleOptionLabel, onSourceRuleChange, newSource, sourceErrors, sourceError,
  editingSourceId, lastSavedSource, showSourceDialog, showSourceDialogGuard, openCreateSource, openEditSource, saveSource, removeSource,
} = useIngestSources(workspace, { selectSources: () => onIngestTab('sources'), cancelSetupEdit, openSetup })
const {
  outputs, outputsLoading, loadOutputs, outputLabel, credentialLabel, editingOutputId, newOutput, outputErrors,
  outputError, outputValidation, credentialAction, showOutputDialog, showOutputDialogGuard, openCreateOutput,
  openEditOutput, checkOutput, addOutput, removeOutput, renderError, renderText, showRender, doRender, copyRender,
} = useIngestOutputs(workspace)
const { parseRules, parseRulesLoading, rulePage, ruleSize, ruleTotal, ruleSearchDraft, loadParseRules, applyRuleSearch, removeParseRule } = useIngestRules(workspace)
const {
  tasks, taskSummary, tasksLoading, summaryStale, taskBusy, loadTasks, toggleTask, toggleTaskRow, healthMeta,
  testDialog, testTarget, testSample, testResult, testLoading, openTest, openTestRow, defaultPreviewSample, runTest,
} = useIngestTasks(workspace)
const { parseFailures, parseFailuresLoading, parseFailurePage, parseFailureSize, parseFailureTotal, replayingFailure, loadParseFailures, replayParseFailure } = useIngestQuarantine(workspace, ingestTab)
const refreshing = ref(false)
let refreshGeneration = 0

function onIngestTab(key: string | number, syncRoute = true) {
  const tab = String(key)
  ingestTab.value = validTabs.includes(tab) ? tab : 'tasks'
  if (syncRoute) {
    clearSetup()
    const query = { ...route.query }; query.tab = ingestTab.value; delete query.sourceId
    if (route.query.tab !== ingestTab.value || route.query.sourceId) void router.push({ query })
  }
  if (tab === 'sources') loadSources()
  if (tab === 'outputs') loadOutputs()
  if (tab === 'rules') loadParseRules()
  if (tab === 'tasks') loadTasks()
  if (tab === 'quarantine') loadParseFailures()
}

function cancelSetupEdit() {
  setupEditGeneration++
  setupEditController?.abort()
  setupEditController = null
}
function clearSetup() {
  cancelSetupEdit()
  showSetup.value = false; setupId.value = ''; setupSample.value = ''
}
function closeSetup(visible = false) {
  if (visible) return
  clearSetup()
  if (route.query.sourceId) {
    const query = { ...route.query }; delete query.sourceId
    void router.replace({ query })
  }
}
function openSetup(id: string, sample = '') {
  cancelSetupEdit()
  setupId.value = id; setupSample.value = sample; showSetup.value = true
  if (route.query.sourceId !== id) void router.push({ query: { ...route.query, sourceId: id } })
}
async function editSourceById(id: string) {
  if (!canWrite.value || !showSetup.value || setupId.value !== id || setupEditController) return
  const generation = setupEditGeneration
  const controller = new AbortController()
  setupEditController = controller
  const ownsResult = () => !workspace.disposed && !controller.signal.aborted && generation === setupEditGeneration
    && canWrite.value && showSetup.value && setupId.value === id
  try {
    const { source } = await getSource(id, { signal: controller.signal })
    if (!ownsResult()) return
    onIngestTab('sources')
    openEditSource(source)
  } catch (failure) {
    if (ownsResult()) actionError.value = String(failure)
  } finally { if (setupEditController === controller) setupEditController = null }
}
function sourceSearch(id: string, eventId?: string, timestamp?: string) {
  const stamp = timestamp ? Date.parse(timestamp) : Date.now()
  const safeStamp = Number.isFinite(stamp) ? stamp : Date.now()
  void router.push({ name: 'search', query: {
    q: `${eventId ? 'eventId' : 'source_id'}=${JSON.stringify(eventId || id)}`, range: 'custom',
    from: new Date(safeStamp - (eventId ? 3600000 : 86400000)).toISOString(),
    to: new Date(Math.max(Date.now(), safeStamp + 3600000)).toISOString(),
  } })
}
function repairFailure(row: IngestParseFailure) {
  if (row.sourceId) openSetup(row.sourceId, row.rawPayload)
  else actionError.value = copy.value.noSource
}
watch(() => route.query.tab, value => {
  const tab = validTabs.includes(String(value)) ? String(value) : 'tasks'
  if (tab !== ingestTab.value) onIngestTab(tab, false)
})
watch(() => route.query.sourceId, id => {
  if (typeof id !== 'string' || !id) { clearSetup(); return }
  if (id !== setupId.value) { cancelSetupEdit(); setupSample.value = '' }
  setupId.value = id; showSetup.value = true
}, { immediate: true, flush: 'sync' })
watch(canWrite, allowed => { if (!allowed) cancelSetupEdit() }, { flush: 'sync' })
async function refreshAll() {
  if (workspace.disposed) return
  const generation = ++refreshGeneration
  actionError.value = ''
  loadErrors.value = {}
  refreshing.value = true
  try {
    await Promise.all([loadSources(), loadOutputs(), loadParseRules(), loadTasks(), loadCategories(), loadParseFailures()])
  } finally { if (generation === refreshGeneration) refreshing.value = false }
}
onMounted(() => { void refreshAll() })
onUnmounted(cancelSetupEdit)
</script>

<template>
  <div class="page-pad view-enter">
    <ActionFeedback :error="loadError" />
    <el-alert v-if="summaryStale" :title="t('ingest.summaryStale')" type="warning" :closable="false" show-icon />
    <ActionFeedback :error="actionError" />
    <PageHeader :eyebrow="t('experience.configuration')" :title="t('ingest.title')" :description="t('ingest.description')">
      <template #actions>
        <el-button size="small" :loading="refreshing" :disabled="refreshing" @click="refreshAll">{{ t('common.refresh') }}</el-button>
      </template>
    </PageHeader>
    <section class="workflow-guide" :aria-label="t('workflow.ingestSteps')">
      <strong>{{ t('workflow.ingestSteps') }}</strong><p>{{ t('workflow.ingestGuide') }}</p>
      <el-button v-if="canWrite" size="small" @click="openCreateSource()">{{ t('workflow.startConfiguration') }}</el-button>
      <el-button size="small" @click="onIngestTab('tasks')">{{ t('ingest.tasks') }}</el-button>
      <el-button size="small" @click="onIngestTab('quarantine')">{{ t('ingest.parseFailureTab') }}</el-button>
    </section>
    <el-alert v-if="lastSavedSource" :title="t('workflow.sourceSaved', { name: lastSavedSource.name })" type="success" :closable="true" @close="lastSavedSource = null" />
    <el-tabs v-model="ingestTab" @tab-change="onIngestTab">
      <el-tab-pane :label="t('ingest.tasks')" name="tasks">
        <div class="add-bar"><el-button v-if="canWrite" type="primary" @click="openCreateSource()">{{ tasks.length ? t('ingest.addSource') : copy.firstSource }}</el-button><span class="hint">{{ copy.firstHint }}</span></div>
        <p class="hint">{{ copy.observations }} {{ copy.debugHint }}</p>
        <el-row class="metrics-row" :gutter="12" style="margin-bottom:14px">
          <el-col :xs="24" :sm="8" :md="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ taskSummary ? `${taskSummary.enabledSources}/${taskSummary.sources}` : t('time.notAvailable') }}</div><div class="label">{{ t('ingest.enabledTotal') }}</div></div></el-card></el-col>
          <el-col :xs="24" :sm="8" :md="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-accent-fg)">{{ taskSummary?.eps1m ?? t('time.notAvailable') }}</div><div class="label">{{ t('ingest.eps') }}</div></div></el-card></el-col>
          <el-col :xs="24" :sm="8" :md="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-success)">{{ taskSummary?.accepted ?? t('time.notAvailable') }}</div><div class="label">{{ t('ingest.accepted') }}</div></div></el-card></el-col>
          <el-col :xs="24" :sm="8" :md="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ taskSummary?.forwarded ?? t('time.notAvailable') }}</div><div class="label">{{ copy.debugForwarded }}</div></div></el-card></el-col>
          <el-col :xs="24" :sm="8" :md="4"><el-card shadow="never"><div class="stat-card"><div class="num" :style="{ color: (taskSummary?.skipped ?? 0) > 0 ? 'var(--ns-warning)' : 'var(--ns-text-3)' }">{{ taskSummary?.skipped ?? t('time.notAvailable') }}</div><div class="label">{{ t('ingest.skipped') }}</div></div></el-card></el-col>
          <el-col :xs="24" :sm="8" :md="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ taskSummary ? fmtBytes(taskSummary.bytes) : t('time.notAvailable') }}</div><div class="label">{{ t('ingest.cumulativeBytes') }}</div></div></el-card></el-col>
        </el-row>
        <el-card shadow="never">
          <template #header><div style="display:flex;align-items:center;gap:10px"><span>{{ t('ingest.taskConfigMetrics') }}</span><el-tag v-for="(count, health) in (taskSummary?.byHealth ?? {})" :key="health" size="small" :type="healthMeta(String(health)).type" style="margin-left:2px">{{ healthMeta(String(health)).text }} {{ count }}</el-tag><el-button size="small" style="margin-left:auto" :loading="tasksLoading" @click="loadTasks">{{ t('common.refresh') }}</el-button></div></template>
          <el-table v-loading="tasksLoading" :data="tasks" size="small" border :empty-text="t(loadErrors.tasks ? 'experience.unknownData' : 'common.empty')">
            <el-table-column :label="t('ingest.status')" width="150"><template #default="{ row }"><el-tag :type="healthMeta(row.runtime.health).type" size="small" effect="dark">{{ healthMeta(row.runtime.health).text }}</el-tag><div class="mono" style="margin-top:3px;font-size:10px;color:var(--ns-text-3)">{{ row.applyState === 'MANUAL_APPLY_REQUIRED' ? t('ingest.pendingManualApply') : t('ingest.configDisabled') }}</div></template></el-table-column>
            <el-table-column :label="t('ingest.task')" min-width="150" show-overflow-tooltip><template #default="{ row }"><div style="font-weight:600">{{ row.name }}</div><div class="mono" style="font-size:11px;color:var(--ns-text-3)">{{ row.collector }}</div></template></el-table-column>
            <el-table-column prop="type" :label="t('ingest.ingestMethod')" width="110" />
            <el-table-column prop="format" :label="t('ingest.parseFormat')" width="90" />
            <el-table-column :label="t('ingest.target')" min-width="180" show-overflow-tooltip><template #default="{ row }"><span class="mono" style="font-size:12px">{{ row.target }}</span></template></el-table-column>
            <el-table-column :label="t('ingest.epsWindow')" width="110"><template #default="{ row }"><span :style="{ color: row.runtime.eps1m > 0 ? 'var(--ns-success)' : 'var(--ns-text-3)', fontWeight: 600 }">{{ row.runtime.eps1m }}</span><span style="color:var(--ns-text-3)"> / {{ row.runtime.eps5m }}</span></template></el-table-column>
            <el-table-column :label="copy.receivedDebugFailed" width="180"><template #default="{ row }"><span class="mono" style="font-size:12px">{{ row.runtime.accepted }} / {{ row.runtime.forwarded }} / <span :style="{ color: row.runtime.parseFailed > 0 ? 'var(--ns-warning)' : 'inherit' }">{{ row.runtime.parseFailed }}</span></span></template></el-table-column>
            <el-table-column :label="t('ingest.recentData')" width="150"><template #default="{ row }"><span class="mono" style="font-size:12px">{{ fmtTime(row.runtime.lastAt) }}</span></template></el-table-column>
            <el-table-column :label="t('ingest.actions')" width="320"><template #default="{ row }"><el-button v-if="canWrite" link :type="row.enabled ? 'warning' : 'success'" size="small" :loading="taskBusy[row.id]" :disabled="confirming || actionBusy" @click="toggleTaskRow(row)">{{ row.enabled ? t('ingest.disableConfig') : t('ingest.enableConfig') }}</el-button><el-button v-if="canWrite" link type="primary" size="small" @click="openSetup(row.id)">{{ copy.setup }}</el-button><el-button link size="small" @click="sourceSearch(row.id)">{{ copy.sourceSearch }}</el-button><el-button v-if="canWrite" link type="primary" size="small" @click="openTestRow(row)">{{ t('ingest.parsePreview') }}</el-button></template></el-table-column>
            <el-table-column type="expand"><template #default="{ row }"><div style="padding:8px 20px;font-size:12px;color:var(--ns-text-2)"><div>{{ t('ingest.environmentDetail', { value: row.env || t('time.notAvailable') }) }} · {{ t('ingest.categoryDetail', { value: row.categoryId || t('time.notAvailable') }) }} · {{ t('ingest.outputDetail', { value: outputLabel(row.sinkTargetId) }) }} · {{ t('ingest.createdDetail', { value: fmtTime(row.createdAt) }) }}</div><div style="margin-top:4px">{{ t('ingest.boundRules') }}<el-tag v-for="p in row.parseRuleIds" :key="p" size="small" style="margin-right:4px">{{ p }}</el-tag><span v-if="!row.parseRuleIds?.length" style="color:var(--ns-text-3)">{{ t('ingest.autoDetect') }}</span></div><div v-if="row.runtime.lastError" style="margin-top:4px;color:var(--ns-danger)">{{ t('ingest.recentError', { time: fmtTime(row.runtime.lastErrorAt ?? null) }) }}{{ row.runtime.lastError }}</div></div></template></el-table-column>
          </el-table>
        </el-card>
        <el-dialog v-model="testDialog" :title="t('ingest.parsePreviewTitle', { name: testTarget?.name ?? '' })" width="720px">
          <div style="font-size:12px;color:var(--ns-text-3);margin-bottom:8px">{{ t('ingest.parsePreviewDescription') }}</div>
          <el-input v-model="testSample" type="textarea" :rows="4" :placeholder="copy.sample" />
          <el-button size="small" @click="testSample = defaultPreviewSample(testTarget!)">{{ copy.example }}</el-button><p class="hint">{{ copy.previewHint }}</p><div v-if="testResult" style="margin-top:12px"><el-alert :type="testResult.ok ? 'success' : 'error'" :closable="false" :title="t(testResult.ok ? 'ingest.parsePreviewPassed' : 'ingest.parsePreviewFailed')" /><div v-if="Object.keys(testResult.fields).length" class="parse-preview-fields"><span v-for="(value, field) in testResult.fields" :key="field"><b>{{ field }}</b><code>{{ value }}</code></span></div><pre class="mono test-out">{{ JSON.stringify(testResult, null, 2) }}</pre></div>
          <template #footer><el-button @click="testDialog = false">{{ t('ingest.close') }}</el-button><el-button type="primary" :loading="testLoading" :disabled="!testSample.trim()" @click="runTest">{{ t('ingest.runParsePreview') }}</el-button></template>
        </el-dialog>
      </el-tab-pane>

      <el-tab-pane :label="t('ingest.sourcesTab')" name="sources">
        <div class="add-bar"><el-button v-if="canWrite" type="primary" @click="openCreateSource">+ {{ t('ingest.addSource') }}</el-button><el-input v-model="sourceSearchDraft" clearable maxlength="128" :placeholder="t('ingest.sourceSearchPlaceholder')" style="width:min(280px,100%)" @keyup.enter="applySourceSearch" @clear="applySourceSearch" /><el-button @click="applySourceSearch">{{ t('common.search') }}</el-button><el-button :loading="sourcesLoading" @click="loadSources">{{ t('ingest.refresh') }}</el-button><el-button type="primary" plain @click="doRender">{{ t('ingest.renderConfig') }}</el-button><span class="hint">{{ copy.builtins }}</span></div>
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


                <FormField :label="t('ingest.outputTarget')">
                  <el-select v-model="newSource.sinkTargetId" clearable filterable :placeholder="t('ingest.outputTargetPlaceholder')"><el-option :label="copy.platformDefault" value="" /><el-option v-if="newSource.sinkTargetId && !outputs.some(output => output.id === newSource.sinkTargetId)" :label="newSource.sinkTargetId" :value="newSource.sinkTargetId" /><el-option v-for="output in outputs" :key="output.id" :label="`${output.name} · ${output.type}`" :value="output.id" /></el-select>
                </FormField>


                <FormField :label="t('common.enabled')">
                  <el-switch v-model="newSource.enabled" />
                </FormField>
              </FormGrid>
            </FormSection>
            <FormSection v-if="newSource.type === 'FILE'" index="02" :title="t('ingest.sourceAccess')" :hint="t('ingest.fileAccessHint')">
              <FormGrid :columns="2">
                <FormField :label="t('ingest.filePath')" :required="newSource.enabled" :error="sourceErrors.path" :hint="copy.localPath"><el-input v-model="newSource.path" :placeholder="t('ingest.filePathPlaceholder')" /></FormField>
                <FormField :label="t('ingest.readFrom')"><el-select v-model="newSource.readFrom"><el-option :label="t('ingest.readFromBeginning')" value="beginning" /><el-option :label="t('ingest.readFromEnd')" value="end" /></el-select></FormField>
              </FormGrid>
              <details><summary>{{ t('ingest.fileOptions') }}</summary><FormGrid :columns="2">
                <FormField :label="t('ingest.multilineLabel')" :hint="t('ingest.multilineHint')" full>
                  <el-input v-model="newSource.multiline" type="textarea" :rows="2" :placeholder="MULTILINE_PLACEHOLDER" />
                </FormField>
                <FormField :label="t('ingest.frequency')"><el-input-number v-model="newSource.frequency" :min="1" controls-position="right" /></FormField>
                <FormField :label="t('ingest.ignoreOlderSeconds')" :hint="t('ingest.ignoreOlderHint')"><el-input-number v-model="newSource.ignoreOlderSeconds" :min="1" :max="315360000" clearable controls-position="right" /></FormField>
              </FormGrid></details>
            </FormSection>
            <FormSection v-else-if="newSource.type === 'SOCKET' || newSource.type === 'SYSLOG'" index="02" :title="t('ingest.sourceAccess')" :hint="t('ingest.socketAccessHint')">
              <FormGrid :columns="2">
                <FormField :label="t('ingest.listenAddress')" :required="newSource.enabled" :error="sourceErrors.address"><el-input v-model="newSource.address" :placeholder="t('ingest.listenAddressPlaceholder')" /></FormField>
                <FormField :label="t('ingest.protocol')"><el-select v-model="newSource.protocol" :placeholder="t('ingest.protocol')"><el-option label="UDP" value="udp" /><el-option label="TCP" value="tcp" /></el-select></FormField>
              </FormGrid>
            </FormSection>
            <FormSection v-else-if="newSource.type === 'KAFKA'" index="02" :title="t('ingest.sourceAccess')" :hint="t('ingest.kafkaAccessHint')">
              <FormGrid :columns="2">
                <FormField :label="copy.brokers" :required="newSource.enabled" :error="sourceErrors.address" :hint="copy.kafkaHint"><el-input v-model="newSource.address" placeholder="broker.example:9092" /></FormField><FormField :label="t('ingest.topic')" :required="newSource.enabled" :error="sourceErrors.topic"><el-input v-model="newSource.topic" :placeholder="t('ingest.topicPlaceholder')" /></FormField>
                <FormField :label="t('ingest.groupId')"><el-input v-model="newSource.groupId" :placeholder="t('ingest.groupIdPlaceholder')" /></FormField>
              </FormGrid>
            </FormSection>
          <div v-else-if="['WINDOWS_EVENT', 'AGENT', 'HTTP_API', 'DATABASE', 'CLOUD'].includes(newSource.type)" style="margin-top:10px"><el-alert type="info" :closable="false" :title="copy.externalBlocked" /></div>
            <details><summary>{{ t('ingest.encodingLabels') }}</summary><FormSection index="03" :title="t('ingest.encodingLabels')" :hint="t('ingest.encodingLabelsHint')">
              <FormGrid :columns="2">
                <FormField :label="t('ingest.category')">
                  <el-select v-model="newSource.categoryId" filterable default-first-option clearable :placeholder="t('meta.authPlaceholder')"><el-option v-for="category in logCategories" :key="category.id" :label="category.code + ' ' + category.name" :value="category.id" /></el-select>
                </FormField>
                <FormField :label="t('ingest.boundRules')" full>
                  <el-select :key="sourceRuleSelectionVersion" v-model="newSource.parseRuleIds" multiple collapse-tags filterable remote :remote-method="searchSourceRules" :loading="sourceRuleLoading" :placeholder="t('ingest.autoDetect')" @change="onSourceRuleChange"><el-option v-for="rule in sourceRuleOptions" :key="rule.id" :label="rule.name" :value="rule.id" /><el-option v-for="id in newSource.parseRuleIds.filter(id => !sourceRuleOptions.some(rule => rule.id === id))" :key="id" :label="sourceRuleOptionLabel(id)" :value="id" /><el-option v-if="sourceRuleTotal > 50" value="__more_rules__" :label="t('ingest.ruleSearchMore', { count: sourceRuleTotal })" disabled /></el-select>
                  <small v-if="sourceRuleTotal > 50" class="hint">{{ t('ingest.ruleSearchMore', { count: sourceRuleTotal }) }}</small>
                  <ActionFeedback :error="loadErrors.ruleOptions || loadErrors.selectedRules" />
                </FormField>
                <FormField :label="t('ingest.environment')">
                  <el-input v-model="newSource.env" :placeholder="t('ingest.environmentPlaceholder')" />
                </FormField>
                <FormField :label="t('common.description')" full>
                  <el-input v-model="newSource.description" type="textarea" :rows="2" :placeholder="t('ingest.sourceDescriptionPlaceholder')" />
                </FormField>
                <FormField :label="t('ingest.charset')"><el-select v-model="newSource.charset" :placeholder="t('ingest.charsetPlaceholder')"><el-option label="UTF-8" value="utf-8" /></el-select></FormField>
                <FormField :label="t('ingest.timezone')"><el-select v-model="newSource.timezone" :placeholder="t('ingest.timezonePlaceholder')"><el-option label="Asia/Shanghai" value="Asia/Shanghai" /><el-option label="UTC" value="UTC" /><el-option label="Asia/Tokyo" value="Asia/Tokyo" /></el-select></FormField>
                <FormField :label="t('ingest.timeField')"><el-input v-model="newSource.timeField" :placeholder="t('ingest.timeFieldPlaceholder')" /></FormField>
                <FormField :label="t('ingest.tags')" :hint="t('ingest.tagsHint')" full><el-input v-model="newSource.tags" :placeholder="t('ingest.tagsPlaceholder')" /></FormField>
              </FormGrid>
            </FormSection></details>
          </el-form>
          <template #footer><el-button @click="showSourceDialogGuard.cancel">{{ t('common.cancel') }}</el-button><el-button v-if="canWrite" :disabled="actionBusy" @click="saveSource(true)">{{ copy.draft }}</el-button><el-button v-if="canWrite" type="primary" :loading="actionBusy" @click="saveSource()">{{ editingSourceId ? t('common.save') : t('ingest.addSource') }}</el-button></template>
        </el-drawer>
        <el-card shadow="never"><el-table v-loading="sourcesLoading" :data="sources" size="small" border :empty-text="t(loadErrors.sources ? 'experience.unknownData' : 'common.empty')"><el-table-column prop="name" :label="t('common.name')" width="130" show-overflow-tooltip /><el-table-column prop="type" :label="t('common.type')" width="110" /><el-table-column prop="format" :label="t('ingest.parseFormat')" width="80" /><el-table-column :label="t('ingest.target')" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.path || row.address || row.topic || t('time.notAvailable') }}</template></el-table-column><el-table-column :label="t('ingest.protocol')" width="70"><template #default="{ row }">{{ row.protocol || t('time.notAvailable') }}</template></el-table-column><el-table-column prop="env" :label="t('ingest.environment')" width="65" /><el-table-column :label="t('common.enabled')" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.yes') : t('common.no') }}</el-tag></template></el-table-column><el-table-column v-if="canWrite" :label="t('common.actions')" width="230"><template #default="{ row }"><el-button link type="primary" size="small" @click="openSetup(row.id)">{{ copy.setup }}</el-button><el-button link type="primary" size="small" @click="openEditSource(row as LogSource)">{{ t('common.edit') }}</el-button><el-button link type="danger" size="small" @click="removeSource(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table><PagerBar v-model:current-page="sourcePage" v-model:page-size="sourceSize" :total="sourceTotal" :page-sizes="[20, 50, 100]" /></el-card>
      </el-tab-pane>

      <el-tab-pane :label="t('ingest.parseFailureTab')" name="quarantine">
        <div class="add-bar"><el-button :loading="parseFailuresLoading" @click="loadParseFailures">{{ t('common.refresh') }}</el-button><span class="hint">{{ t('ingest.parseFailureHint') }}</span></div>
        <ActionFeedback :error="loadErrors.quarantine" />
        <el-card shadow="never">
          <el-table v-loading="parseFailuresLoading" :data="parseFailures" size="small" border :empty-text="t(loadErrors.quarantine ? 'experience.unknownData' : 'common.empty')">
            <el-table-column prop="receivedAt" :label="t('ingest.receivedAt')" width="170"><template #default="{ row }">{{ fmtTime(row.receivedAt) }}</template></el-table-column>
            <el-table-column prop="collectorId" :label="t('ingest.collector')" width="150" show-overflow-tooltip />
            <el-table-column prop="rawPayload" :label="t('ingest.rawPayload')" min-width="240" show-overflow-tooltip />
            <el-table-column :label="t('ingest.failureReason')" min-width="220" show-overflow-tooltip><template #default="{ row }"><span :class="{ 'parse-replay-error': row.lastError }">{{ row.lastError || row.failureReason }}</span></template></el-table-column>
            <el-table-column prop="parserVersion" :label="t('ingest.parserVersion')" width="130" show-overflow-tooltip />
            <el-table-column prop="replayAttempts" :label="t('ingest.replayAttempts')" width="90" />
            <el-table-column prop="replayedEventId" :label="t('ingest.replayedEventId')" min-width="180"><template #default="{ row }"><el-button v-if="row.replayedEventId" link @click="sourceSearch(row.sourceId || '', row.replayedEventId, row.eventTimestamp || row.receivedAt)">{{ row.replayedEventId }}</el-button></template></el-table-column>
            <el-table-column prop="replayStatus" :label="t('common.status')" width="105"><template #default="{ row }"><el-tag :type="row.replayStatus === 'REPLAYED' ? 'success' : 'warning'" size="small">{{ row.replayStatus }}</el-tag></template></el-table-column>
            <el-table-column v-if="canWrite" :label="t('common.actions')" width="200"><template #default="{ row }"><el-button link size="small" @click="repairFailure(row as IngestParseFailure)">{{ copy.repair }}</el-button><el-button link type="primary" size="small" :loading="replayingFailure === row.id" :disabled="row.replayStatus === 'REPLAYED' || !!replayingFailure" @click="replayParseFailure(row as IngestParseFailure)">{{ t('ingest.replay') }}</el-button></template></el-table-column>
          </el-table>
          <PagerBar v-model:current-page="parseFailurePage" v-model:page-size="parseFailureSize" :total="parseFailureTotal" :page-sizes="[20, 50, 100]" />
        </el-card>
      </el-tab-pane>

      <el-tab-pane :label="t('ingest.outputTab')" name="outputs">
        <div class="add-bar"><el-button v-if="canWrite" type="primary" @click="openCreateOutput">+ {{ t('ingest.addOutput') }}</el-button><span class="hint">{{ copy.outputHint }}</span></div>
        <el-dialog v-model="showOutputDialog" :before-close="showOutputDialogGuard.beforeClose" :title="editingOutputId ? t('common.edit') : t('ingest.addOutput')" width="520px"><ActionFeedback :error="outputError" /><el-form :disabled="actionBusy" label-position="top">
            <FormGrid :columns="2">
              <FormField :label="t('ingest.outputName')" required :error="outputErrors.name">
                <el-input v-model="newOutput.name" :placeholder="t('ingest.addOutputNamePlaceholder')" />
              </FormField>
              <FormField :label="t('ingest.outputType')" :hint="t('ingest.outputTypeHint')" :error="outputErrors.type">
                <el-select v-model="newOutput.type"><el-option label="GLS_INGEST" value="GLS_INGEST" /><el-option v-if="!['GLS_INGEST', 'HTTP'].includes(newOutput.type)" :label="newOutput.type + ' · ' + t('workflow.unsupported')" :value="newOutput.type" disabled /><el-option label="HTTP" value="HTTP" /></el-select>
              </FormField>
              <FormField :label="t('ingest.targetUrl')" required :hint="t('ingest.outputUriHint')" :error="outputErrors.uri" full>
                <el-input v-model="newOutput.uri" :placeholder="t('ingest.addOutputUrlPlaceholder')" />
              </FormField>
              <FormField :label="t('ingest.outputAuthToken')" :hint="t('ingest.outputAuthTokenHint')" :error="outputErrors.authToken" full>
                <el-select v-if="editingOutputId" v-model="credentialAction" :aria-label="t('workflow.credentialAction')"><el-option v-for="action in ['KEEP', 'REPLACE', 'CLEAR']" :key="action" :value="action" :label="credentialLabel(action)" /></el-select>
                <el-input v-if="!editingOutputId || credentialAction === 'REPLACE'" v-model="newOutput.authToken" type="password" show-password :placeholder="t('ingest.outputAuthTokenPlaceholder')" />
              </FormField>
              <FormField :label="t('common.enabled')">
                <el-switch v-model="newOutput.enabled" />
              </FormField>
            </FormGrid>
          </el-form><p v-if="outputValidation" role="status">{{ outputValidation }}</p><template #footer><el-button :disabled="actionBusy" @click="checkOutput">{{ copy.validate }}</el-button><el-button @click="showOutputDialogGuard.cancel">{{ t('common.cancel') }}</el-button><el-button v-if="canWrite" type="primary" :loading="actionBusy" @click="addOutput">{{ editingOutputId ? t('common.save') : t('ingest.addOutput') }}</el-button></template></el-dialog>
        <el-card shadow="never"><el-table v-loading="outputsLoading" :data="outputs" size="small" border :empty-text="t(loadErrors.outputs ? 'experience.unknownData' : 'common.empty')"><el-table-column prop="name" :label="t('common.name')" width="180" /><el-table-column prop="type" :label="t('common.type')" width="130" /><el-table-column prop="uri" :label="t('ingest.targetUrl')" min-width="280" show-overflow-tooltip /><el-table-column :label="t('common.enabled')" width="70"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.yes') : t('common.no') }}</el-tag></template></el-table-column><el-table-column v-if="canWrite" :label="t('common.actions')" width="150"><template #default="{ row }"><el-button link type="primary" size="small" @click="openEditOutput(row as SinkTarget)">{{ t('common.edit') }}</el-button><el-button link type="danger" size="small" @click="removeOutput(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table></el-card>
      </el-tab-pane>

      <el-tab-pane :label="t('ingest.rulesTab')" name="rules">
        <div class="add-bar"><el-button v-if="canWrite" type="primary" @click="router.push({ name: 'parser-new' })">{{ t('ingest.addParseRule') }}</el-button><el-input v-model="ruleSearchDraft" clearable maxlength="128" :placeholder="t('ingest.ruleSearchPlaceholder')" style="width:min(280px,100%)" @keyup.enter="applyRuleSearch" @clear="applyRuleSearch" /><el-button @click="applyRuleSearch">{{ t('common.search') }}</el-button><el-button :loading="parseRulesLoading" @click="loadParseRules">{{ t('ingest.refresh') }}</el-button><span class="hint">{{ t('ingest.parserHint') }}</span></div>
        <el-card shadow="never"><el-table v-loading="parseRulesLoading" :data="parseRules" size="small" border :empty-text="t(loadErrors.rules ? 'experience.unknownData' : 'common.empty')"><el-table-column prop="name" :label="t('ingest.ruleName')" width="180" /><el-table-column prop="format" :label="t('ingest.parseFormat')" width="90" /><el-table-column prop="pattern" :label="t('ingest.patternDescription')" min-width="300" show-overflow-tooltip /><el-table-column :label="t('common.enabled')" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.yes') : t('common.no') }}</el-tag></template></el-table-column><el-table-column v-if="canWrite" :label="t('common.actions')" width="150"><template #default="{ row }"><el-button link size="small" @click="router.push({ name: 'parser-edit', params: { parserId: row.id } })">{{ t('common.edit') }}</el-button><el-button link type="danger" size="small" @click="removeParseRule(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table><PagerBar v-model:current-page="rulePage" v-model:page-size="ruleSize" :total="ruleTotal" :page-sizes="[20, 50, 100]" /></el-card>
      </el-tab-pane>
    </el-tabs>
    <el-drawer :model-value="showSetup" @update:model-value="closeSetup" :title="copy.setup" size="min(820px, 96vw)"><IngestSourceSetup v-if="showSetup" :source-id="setupId" :initial-sample="setupSample" @edit="editSourceById" /></el-drawer>
    <el-dialog v-model="showRender" title="vector.toml" width="720px"><ActionFeedback :error="renderError" /><p class="workflow-guide">{{ t('workflow.applyConfig') }}</p><el-button size="small" type="primary" @click="copyRender">{{ t('common.copy') }}</el-button><pre style="background:var(--ns-bg-subtle);border:1px solid var(--ns-border);border-radius:6px;padding:12px;font-size:12px;overflow:auto;max-height:440px;margin-top:10px">{{ renderText }}</pre></el-dialog>

  </div>
</template>
