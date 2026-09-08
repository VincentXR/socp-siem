<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/switch/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, inject, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'
import FieldConditionBuilder from '../components/FieldConditionBuilder.vue'
import SevBadge from '../components/SevBadge.vue'
import { WORKBENCH_STATE } from '../app/workbenchState'
import {
  activateGasRule, createGasRule, deleteGasRule, gasStats, listRules, SEVERITIES, updateGasRule,
  listFields, listRefSets,
  type FieldDef, type GasStats, type ReferenceSet, type RuleCondition, type RuleSpec,
} from '../api'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()
const state = inject(WORKBENCH_STATE, null)
const currentRole = computed(() => state?.currentRole.value ?? '')
const canActivate = computed(() => ['admin', 'role_admin'].includes(currentRole.value.toLowerCase()))
const canManageRules = computed(() => ['admin', 'role_admin', 'analyst', 'role_analyst'].includes(currentRole.value.toLowerCase()))

type RuleEditorForm = {
  id: string; name: string; type: string; severity: string; message: string
  alertTitle: string; alertDescription: string; enabled: boolean; status: string
  window: string; keyField: string; routingField: string; threshold: number | null
  valueField: string; warmup: number | null; baselineWindows: number | null; sigma: number | null
  minCount: number | null; mitre: string; version: string; owner: string
  contentPack: string; contentVersion: string; match: RuleCondition[]; matchAny: RuleCondition[][]
  whitelist: RuleCondition[]; steps: RuleCondition[][]
}

type TestConditionTrace = { condition: RuleCondition; matched: boolean; observed: string }
type RuleTestTrace = {
  id: string; name: string; type: string; state: 'MATCHED' | 'CANDIDATE' | 'NO_MATCH'
  reason: string; conditions: TestConditionTrace[]
}
type RuleTestResult = { checked: number; matched: number; candidates: number; traces: RuleTestTrace[] }

const RULE_TYPES = ['pattern', 'threshold', 'correlation', 'correlation-set', 'baseline', 'rare']
const ADVANCED_TYPES = ['correlation-set', 'baseline', 'rare']

const allRules = ref<RuleSpec[]>([])
const fieldDefs = ref<FieldDef[]>([])
const referenceSets = ref<ReferenceSet[]>([])
const gasStat = ref<GasStats>({ rules: 0, eventCount: 0, alertCount: 0, dropCount: 0, suppressedCount: 0, queueLoad: 0 })
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const fieldLoadError = ref('')
const actionMessage = ref('')
const saveError = ref('')
const showRuleEditor = ref(false)
const ruleEditingId = ref<string | null>(null)
const sourceRule = ref<RuleSpec | null>(null)
const advancedJson = ref('{}')
const advancedError = ref('')
const ruleStatusFilter = ref('')
const testInput = ref({
  source: 'auth', host: 'workbench-sample', severity: 'HIGH', message: 'Failed password for admin',
  fieldsText: '{\n  "user": "admin",\n  "attempts": 6\n}',
})
const testRuleId = ref('')
const testError = ref('')
const testResult = ref<RuleTestResult | null>(null)

function emptyCondition(): RuleCondition { return { field: 'msg', op: 'contains', value: '' } }
function clone<T>(value: T): T { return value == null ? value : JSON.parse(JSON.stringify(value)) as T }
function cloneConditions(conditions: RuleCondition[] | undefined): RuleCondition[] { return conditions?.length ? clone(conditions) : [emptyCondition()] }
function cloneConditionGroups(groups: RuleCondition[][] | undefined): RuleCondition[][] { return groups?.length ? clone(groups) : [] }

function emptyRuleForm(): RuleEditorForm {
  return {
    id: '', name: '', type: 'pattern', severity: 'HIGH', message: '', alertTitle: '', alertDescription: '', enabled: false,
    status: 'DRAFT', window: '60s', keyField: 'src_ip', routingField: '', threshold: 5, valueField: '', warmup: null,
    baselineWindows: null, sigma: null, minCount: null, mitre: '', version: '', owner: '', contentPack: '', contentVersion: '',
    match: [emptyCondition()], matchAny: [], whitelist: [], steps: [],
  }
}

function textValue(value: unknown): string { return value == null ? '' : String(value) }
function numberValue(value: unknown): number | null { const parsed = Number(value); return Number.isFinite(parsed) ? parsed : null }

function formFromRule(rule: RuleSpec): RuleEditorForm {
  return {
    id: textValue(rule.id), name: textValue(rule.name), type: textValue(rule.type || 'pattern'), severity: textValue(rule.severity || 'HIGH'),
    message: textValue(rule.message || rule.alert?.description), alertTitle: textValue(rule.alert?.title || rule.name),
    alertDescription: textValue(rule.alert?.description || rule.message), enabled: Boolean(rule.enabled),
    status: textValue(rule.status || (rule.enabled ? 'ACTIVE' : 'DRAFT')).toUpperCase(), window: textValue(rule.window),
    keyField: textValue(rule.keyField), routingField: textValue(rule.routingField), threshold: numberValue(rule.threshold),
    valueField: textValue(rule.valueField), warmup: numberValue(rule.warmup), baselineWindows: numberValue(rule.baselineWindows),
    sigma: numberValue(rule.sigma), minCount: numberValue(rule.minCount), mitre: textValue(rule.mitre), version: textValue(rule.version),
    owner: textValue(rule.owner), contentPack: textValue(rule.contentPack), contentVersion: textValue(rule.contentVersion),
    match: cloneConditions(rule.match), matchAny: cloneConditionGroups(rule.matchAny), whitelist: clone(rule.whitelist ?? []), steps: clone(rule.steps ?? []),
  }
}

const ruleForm = ref<RuleEditorForm>(emptyRuleForm())
const rawOnlyRuleType = computed(() => Boolean(ruleForm.value.type) && !RULE_TYPES.includes(ruleForm.value.type))
const visibleRules = computed(() => ruleStatusFilter.value
  ? allRules.value.filter(rule => ruleStatus(rule) === ruleStatusFilter.value)
  : allRules.value)
const rules = computed(() => visibleRules.value)

function normalizeRuleSpec(row: unknown): RuleSpec | null {
  if (!row || typeof row !== 'object') return null
  const candidate = row as Partial<RuleSpec>
  if (candidate.id == null || !String(candidate.id).trim() || candidate.name == null || !String(candidate.name).trim()) return null
  const status = textValue(candidate.status).toUpperCase()
  return {
    ...candidate, id: String(candidate.id), name: String(candidate.name), type: String(candidate.type ?? 'pattern'), severity: String(candidate.severity ?? 'HIGH'),
    status: status || (candidate.enabled ? 'ACTIVE' : 'DRAFT'), enabled: typeof candidate.enabled === 'boolean' ? candidate.enabled : status === 'ACTIVE',
  } as RuleSpec
}

async function loadRules(): Promise<void> {
  if (loading.value) return
  loading.value = true
  loadError.value = ''
  const [ruleResult, statResult, fieldResult, refsetResult] = await Promise.allSettled([listRules(), gasStats(), listFields(), listRefSets()])
  if (ruleResult.status === 'fulfilled') allRules.value = ruleResult.value.map(normalizeRuleSpec).filter((rule): rule is RuleSpec => rule !== null)
  else loadError.value = ruleResult.reason instanceof Error ? ruleResult.reason.message : String(ruleResult.reason)
  if (statResult.status === 'fulfilled') gasStat.value = statResult.value
  if (fieldResult.status === 'fulfilled') {
    fieldDefs.value = fieldResult.value
    fieldLoadError.value = ''
  } else {
    fieldLoadError.value = fieldResult.reason instanceof Error ? fieldResult.reason.message : String(fieldResult.reason)
  }
  if (refsetResult.status === 'fulfilled') referenceSets.value = refsetResult.value
  loading.value = false
}

function openRuleEditor(row?: unknown): void {
  saveError.value = ''; advancedError.value = ''; actionMessage.value = ''
  const rule = row ? normalizeRuleSpec(row) : null
  if (rule) {
    ruleEditingId.value = String(rule.id); sourceRule.value = clone(rule); ruleForm.value = formFromRule(rule); advancedJson.value = JSON.stringify(rule, null, 2)
  } else {
    ruleEditingId.value = null; sourceRule.value = null; ruleForm.value = emptyRuleForm(); advancedJson.value = '{}'
  }
  showRuleEditor.value = true
}

function closeRuleEditor(): void {
  if (saving.value) return
  showRuleEditor.value = false; sourceRule.value = null; advancedError.value = ''
}

function cleanConditions(conditions: RuleCondition[]): RuleCondition[] {
  return conditions.map(condition => ({ field: condition.field.trim(), op: condition.op.trim(), value: condition.value.trim() })).filter(condition => condition.field && condition.op && condition.value)
}
function cleanGroups(groups: RuleCondition[][]): RuleCondition[][] { return groups.map(cleanConditions).filter(group => group.length) }
function setOptional(target: Record<string, unknown>, key: string, value: unknown): void {
  if (value === null || value === undefined || (typeof value === 'string' && !value.trim())) delete target[key]
  else target[key] = value
}

function buildRuleSpec(): Partial<RuleSpec> {
  const spec = (sourceRule.value ? clone(sourceRule.value) : {}) as Record<string, unknown>
  spec.name = ruleForm.value.name.trim(); spec.type = ruleForm.value.type; spec.severity = ruleForm.value.severity
  setOptional(spec, 'message', ruleForm.value.message); setOptional(spec, 'window', ruleForm.value.window); setOptional(spec, 'keyField', ruleForm.value.keyField)
  setOptional(spec, 'routingField', ruleForm.value.routingField); setOptional(spec, 'threshold', ruleForm.value.threshold); setOptional(spec, 'valueField', ruleForm.value.valueField)
  setOptional(spec, 'warmup', ruleForm.value.warmup); setOptional(spec, 'baselineWindows', ruleForm.value.baselineWindows); setOptional(spec, 'sigma', ruleForm.value.sigma)
  setOptional(spec, 'minCount', ruleForm.value.minCount); setOptional(spec, 'mitre', ruleForm.value.mitre); setOptional(spec, 'version', ruleForm.value.version)
  setOptional(spec, 'owner', ruleForm.value.owner); setOptional(spec, 'contentPack', ruleForm.value.contentPack); setOptional(spec, 'contentVersion', ruleForm.value.contentVersion)
  const alert = (spec.alert && typeof spec.alert === 'object' ? clone(spec.alert) : {}) as Record<string, unknown>
  setOptional(alert, 'title', ruleForm.value.alertTitle); setOptional(alert, 'description', ruleForm.value.alertDescription)
  if (Object.keys(alert).length) spec.alert = alert; else delete spec.alert
  spec.match = cleanConditions(ruleForm.value.match); spec.matchAny = cleanGroups(ruleForm.value.matchAny); spec.steps = cleanGroups(ruleForm.value.steps); spec.whitelist = cleanConditions(ruleForm.value.whitelist)

  // Lifecycle transitions are separate operations. An edit cannot promote a
  // draft or disabled rule through the ordinary update endpoint.
  delete spec.status
  if (ruleEditingId.value) {
    spec.id = ruleEditingId.value
    spec.enabled = textValue(sourceRule.value?.status).toUpperCase() === 'ACTIVE' ? ruleForm.value.enabled : false
  } else {
    delete spec.id; spec.enabled = false
  }
  return spec as Partial<RuleSpec>
}

async function saveRule(): Promise<void> {
  saveError.value = ''
  if (!ruleForm.value.name.trim()) { saveError.value = t('detect.nameRequired'); return }
  saving.value = true
  try {
    const spec = buildRuleSpec()
    const saved = ruleEditingId.value ? await updateGasRule(ruleEditingId.value, spec) : await createGasRule(spec)
    const normalized = normalizeRuleSpec(saved)
    if (normalized) { ruleEditingId.value = String(normalized.id); sourceRule.value = clone(normalized); ruleForm.value = formFromRule(normalized); advancedJson.value = JSON.stringify(normalized, null, 2) }
    showRuleEditor.value = false
    await loadRules()
  } catch (error) {
    saveError.value = t('detect.saveFailed', { message: error instanceof Error ? error.message : String(error) })
  } finally { saving.value = false }
}

function applyAdvancedJson(): void {
  advancedError.value = ''
  try {
    const parsed = JSON.parse(advancedJson.value) as unknown
    const normalized = normalizeRuleSpec(parsed)
    if (!normalized) throw new Error(t('detect.invalidRuleJson'))
    sourceRule.value = clone(normalized); ruleEditingId.value = String(normalized.id); ruleForm.value = formFromRule(normalized); advancedJson.value = JSON.stringify(normalized, null, 2)
  } catch (error) { advancedError.value = error instanceof Error ? error.message : String(error) }
}

function ruleUpdateSpec(rule: RuleSpec, enabled: boolean, status: string): Partial<RuleSpec> {
  const spec = clone(rule) as Partial<RuleSpec>; spec.id = String(rule.id); spec.enabled = enabled; spec.status = status; return spec
}

async function toggleRule(row: unknown): Promise<void> {
  const rule = normalizeRuleSpec(row)
  if (!rule) return
  actionMessage.value = ''
  const status = textValue(rule.status).toUpperCase()
  if (status === 'ACTIVE' || rule.enabled) {
    try { await updateGasRule(String(rule.id), ruleUpdateSpec(rule, false, 'DISABLED')); await loadRules() }
    catch (error) { actionMessage.value = error instanceof Error ? error.message : String(error) }
    return
  }
  if (!canActivate.value) { actionMessage.value = t('detect.activationAdminOnly'); return }
  if (status === 'ARCHIVED') return
  try { await activateGasRule(String(rule.id)); await loadRules() }
  catch (error) { actionMessage.value = error instanceof Error ? error.message : String(error) }
}

async function removeRule(row: unknown): Promise<void> {
  const rule = normalizeRuleSpec(row)
  if (!rule) return
  if (ruleStatus(rule) === 'ACTIVE') {
    actionMessage.value = t('detect.disableBeforeDelete')
    return
  }
  if (!confirm(t('detect.deleteRuleConfirm'))) return
  try { await deleteGasRule(String(rule.id)); await loadRules() }
  catch (error) { actionMessage.value = error instanceof Error ? error.message : String(error) }
}

function copyRuleAsDraft(row: unknown): void {
  const rule = normalizeRuleSpec(row)
  if (!rule) return
  const draft = clone(rule)
  draft.name = `${rule.name} · copy`
  draft.enabled = false
  draft.status = 'DRAFT'
  // Keep the full source document in memory so fields unknown to the visual
  // editor survive the eventual create request. The copied draft is saved as
  // a new rule because ruleEditingId is intentionally cleared.
  ruleEditingId.value = null
  sourceRule.value = draft
  ruleForm.value = formFromRule(draft)
  advancedJson.value = JSON.stringify(draft, null, 2)
  saveError.value = ''; advancedError.value = ''; actionMessage.value = ''
  showRuleEditor.value = true
}

function testSingleRule(row: unknown): void {
  const rule = normalizeRuleSpec(row)
  if (!rule) return
  testRuleId.value = String(rule.id)
  document.getElementById('detect-test-workspace')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function addMatchAnyGroup(): void { ruleForm.value.matchAny.push([emptyCondition()]) }
function addStep(): void { ruleForm.value.steps.push([emptyCondition()]) }
function ruleStatus(row: unknown): string {
  const rule = row as Partial<RuleSpec>
  return textValue(rule.status).toUpperCase() || (rule.enabled ? 'ACTIVE' : 'DRAFT')
}
function statusTag(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'ACTIVE') return 'success'; if (status === 'TESTING') return 'warning'; if (status === 'DISABLED') return 'info'; if (status === 'ARCHIVED') return 'danger'; return 'primary'
}
function typeLabel(type: string): string { return ADVANCED_TYPES.includes(type) ? `${type} · ${t('detect.advancedType')}` : type }

type TestEvent = { source: string; host: string; severity: string; msg: string; fields: Record<string, unknown> }
function valueForField(field: string, event: TestEvent): unknown {
  const normalized = field.replace(/^fields\./, '')
  if (field === 'source') return event.source; if (field === 'host') return event.host; if (field === 'severity') return event.severity; if (field === 'msg' || field === 'message') return event.msg
  return event.fields[normalized] ?? event.fields[field]
}
function compareCondition(condition: RuleCondition, value: unknown): boolean {
  const expected = condition.value; const actual = value == null ? '' : String(value); const numberActual = Number(value); const numberExpected = Number(expected)
  switch (condition.op) {
    case 'eq': return actual === expected
    case 'ne': return actual !== expected
    case 'contains': return actual.toLowerCase().includes(expected.toLowerCase())
    case 'startswith': return actual.toLowerCase().startsWith(expected.toLowerCase())
    case 'endswith': return actual.toLowerCase().endsWith(expected.toLowerCase())
    case 'regex': try { return new RegExp(expected).test(actual) } catch { return false }
    case 'gt': case 'gte': case 'ge': case 'lt': case 'lte':
      if (!Number.isFinite(numberActual) || !Number.isFinite(numberExpected)) return false
      if (condition.op === 'gt') return numberActual > numberExpected; if (condition.op === 'lt') return numberActual < numberExpected; if (condition.op === 'lte') return numberActual <= numberExpected; return numberActual >= numberExpected
    case 'inlist': return expected.split(',').map(item => item.trim()).includes(actual)
    case 'notinlist': return !expected.split(',').map(item => item.trim()).includes(actual)
    case 'gtsev': { const levels = ['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL']; return levels.indexOf(actual.toUpperCase()) >= levels.indexOf(expected.toUpperCase()) }
    default: return actual === expected
  }
}
function traceConditions(conditions: RuleCondition[], event: TestEvent): TestConditionTrace[] {
  return conditions.map(condition => { const observed = valueForField(condition.field, event); return { condition: clone(condition), matched: compareCondition(condition, observed), observed: observed == null ? '∅' : String(observed) } })
}
function testRule(rule: RuleSpec, event: TestEvent): RuleTestTrace {
  const main = traceConditions(rule.match ?? [], event); const anyGroups = (rule.matchAny ?? []).map(group => traceConditions(group, event)); const steps = (rule.steps ?? []).map(step => traceConditions(step, event)); const whitelist = traceConditions(rule.whitelist ?? [], event)
  const mainMatched = main.length === 0 || main.every(item => item.matched); const anyMatched = anyGroups.length === 0 || anyGroups.some(group => group.length > 0 && group.every(item => item.matched)); const stepsMatched = steps.length === 0 || steps.every(step => step.length > 0 && step.every(item => item.matched))
  const whitelistMatched = whitelist.some(item => item.matched)
  const matched = main.length + anyGroups.flat().length + steps.flat().length > 0 && mainMatched && anyMatched && stepsMatched && !whitelistMatched; const candidate = matched && ['threshold', 'correlation', 'correlation-set', 'baseline', 'rare'].includes(textValue(rule.type))
  return { id: String(rule.id), name: String(rule.name), type: textValue(rule.type), state: candidate ? 'CANDIDATE' : matched ? 'MATCHED' : 'NO_MATCH', reason: whitelistMatched ? t('detect.testWhitelisted') : candidate ? t('detect.testCandidate') : matched ? t('detect.testMatched') : t('detect.testNoMatch'), conditions: [...main, ...anyGroups.flat(), ...steps.flat(), ...whitelist] }
}
function runIsolatedTest(): void {
  testError.value = ''; testResult.value = null
  try {
    const parsed = JSON.parse(testInput.value.fieldsText) as unknown
    if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error(t('detect.fieldsObjectRequired'))
    const event: TestEvent = { source: testInput.value.source, host: testInput.value.host, severity: testInput.value.severity, msg: testInput.value.message, fields: parsed as Record<string, unknown> }
    const selected = testRuleId.value ? rules.value.filter(rule => String(rule.id) === testRuleId.value) : rules.value; const traces = selected.map(rule => testRule(rule, event))
    testResult.value = { checked: traces.length, matched: traces.filter(trace => trace.state === 'MATCHED').length, candidates: traces.filter(trace => trace.state === 'CANDIDATE').length, traces }
  } catch (error) { testError.value = error instanceof Error ? error.message : String(error) }
}

onMounted(loadRules)
</script>

<template>
  <div class="page-pad view-enter detect-view">
    <PageHeader :eyebrow="t('menuGroup.detectAndResponse')" :title="t('detect.title')" :description="t('detect.workspaceDescription')">
      <template #actions><el-select v-model="ruleStatusFilter" size="small" clearable :placeholder="t('common.filter')" style="width:150px"><el-option v-for="status in ['DRAFT', 'TESTING', 'ACTIVE', 'DISABLED', 'ARCHIVED']" :key="status" :label="status" :value="status" /></el-select><el-button size="small" :loading="loading" @click="loadRules">{{ t('common.refresh') }}</el-button><el-button v-if="canManageRules" type="primary" size="small" @click="openRuleEditor()">{{ t('detect.createRule') }}</el-button></template>
    </PageHeader>

    <div v-if="loadError" class="detect-feedback error" role="alert"><strong>{{ t('detect.loadFailed') }}</strong><span>{{ loadError }}</span><el-button size="small" @click="loadRules">{{ t('common.refresh') }}</el-button></div>
    <div v-if="actionMessage" class="detect-feedback error" role="alert">{{ actionMessage }}</div>

    <div class="detect-stat-grid">
      <el-card shadow="never"><div class="detect-stat"><span>{{ t('detect.rulesCount') }}</span><b>{{ gasStat.rules ?? 0 }}</b></div></el-card>
      <el-card shadow="never"><div class="detect-stat"><span>{{ t('detect.eventsCount') }}</span><b>{{ gasStat.eventCount ?? 0 }}</b></div></el-card>
      <el-card shadow="never"><div class="detect-stat"><span>{{ t('detect.alarmsCount') }}</span><b class="danger-text">{{ gasStat.alertCount ?? 0 }}</b></div></el-card>
      <el-card shadow="never"><div class="detect-stat"><span>{{ t('detect.queueLoad') }}</span><b>{{ (gasStat.queueLoad * 100).toFixed(0) }}%</b></div></el-card>
    </div>

    <section class="detect-test-workspace">
      <div class="workspace-section-head"><div><h2>{{ t('detect.testTitle') }}</h2><p>{{ t('detect.testHint') }}</p></div><el-tag type="info" size="small">{{ t('detect.isolatedTest') }}</el-tag></div>
      <div class="detect-test-grid">
        <div class="detect-test-form">
          <label>{{ t('detect.testRule') }}<el-select v-model="testRuleId" clearable :placeholder="t('detect.allRules')"><el-option :label="t('detect.allRules')" value="" /><el-option v-for="rule in rules" :key="rule.id" :label="rule.name" :value="String(rule.id)" /></el-select></label>
          <label>{{ t('common.source') }}<el-input v-model="testInput.source" /></label><label>{{ t('common.host') }}<el-input v-model="testInput.host" /></label>
          <label>{{ t('common.severity') }}<el-select v-model="testInput.severity"><el-option v-for="severity in SEVERITIES" :key="severity" :label="t('severities.' + severity) || severity" :value="severity" /></el-select></label>
          <label class="full-width">{{ t('detect.testMessage') }}<el-input v-model="testInput.message" /></label><label class="full-width">{{ t('detect.testFields') }}<el-input v-model="testInput.fieldsText" type="textarea" :rows="4" spellcheck="false" /></label>
          <div class="detect-test-actions"><el-button type="primary" :disabled="!rules.length" @click="runIsolatedTest">{{ t('detect.runTest') }}</el-button><span>{{ t('detect.isolatedTestHint') }}</span></div>
        </div>
        <div class="detect-test-result">
          <EmptyState v-if="!testResult && !testError" :title="t('detect.testWaiting')" :description="t('detect.testWaitingHint')" /><div v-if="testError" class="detect-feedback error" role="alert">{{ testError }}</div>
          <template v-if="testResult"><div class="test-summary"><span>{{ t('detect.testChecked', { count: testResult.checked }) }}</span><el-tag type="success" size="small">{{ t('detect.testMatchedCount', { count: testResult.matched }) }}</el-tag><el-tag type="warning" size="small">{{ t('detect.testCandidateCount', { count: testResult.candidates }) }}</el-tag></div>
            <div v-for="trace in testResult.traces" :key="trace.id" class="test-trace" :class="trace.state.toLowerCase()"><div class="test-trace-head"><div><b>{{ trace.name }}</b><span class="mono">{{ trace.id }}</span></div><el-tag size="small" :type="trace.state === 'NO_MATCH' ? 'info' : trace.state === 'CANDIDATE' ? 'warning' : 'success'">{{ trace.state }}</el-tag></div><p>{{ trace.reason }}</p><div v-if="trace.conditions.length" class="test-condition-list"><div v-for="(item, index) in trace.conditions" :key="index" class="test-condition" :class="{ matched: item.matched }"><span class="condition-mark">{{ item.matched ? '✓' : '×' }}</span><span class="mono">{{ item.condition.field }} {{ item.condition.op }} {{ item.condition.value }}</span><span>{{ item.observed }}</span></div></div><div v-else class="test-no-condition">{{ t('detect.testNoConditions') }}</div></div>
          </template>
        </div>
      </div>
    </section>

    <section v-if="showRuleEditor" class="detect-editor-workspace">
      <div class="workspace-section-head"><div><div class="page-eyebrow">{{ t('detect.editorEyebrow') }}</div><h2>{{ ruleEditingId ? t('detect.editor.editRule') : t('detect.createRule') }}</h2><p>{{ t('detect.editorHint') }}</p></div><div class="workspace-section-actions"><el-tag v-if="ruleEditingId" :type="statusTag(ruleForm.status)" size="small">{{ ruleForm.status }}</el-tag><el-button size="small" @click="closeRuleEditor">{{ t('common.cancel') }}</el-button></div></div>
      <div v-if="saveError" class="detect-feedback error" role="alert">{{ saveError }}</div>
      <el-form label-position="top" class="detect-editor-form">
        <section class="detect-form-section"><div class="detect-form-section-title"><span>01</span><div><h3>{{ t('detect.dataScope') }}</h3><p>{{ t('detect.dataScopeHint') }}</p></div></div><div class="detect-form-grid"><el-form-item :label="t('common.name')" required><el-input v-model="ruleForm.name" :placeholder="t('detect.editor.namePlaceholder')" /></el-form-item><el-form-item :label="t('common.type')"><el-select v-model="ruleForm.type"><el-option v-if="rawOnlyRuleType" :label="typeLabel(ruleForm.type)" :value="ruleForm.type" /><el-option v-for="type in RULE_TYPES" :key="type" :label="typeLabel(type)" :value="type" /></el-select></el-form-item><el-form-item :label="t('common.severity')"><el-select v-model="ruleForm.severity"><el-option v-for="severity in SEVERITIES" :key="severity" :label="t('severities.' + severity) || severity" :value="severity" /></el-select></el-form-item><el-form-item :label="t('detect.editor.window')"><el-input v-model="ruleForm.window" :placeholder="t('detect.editor.windowPlaceholder')" /></el-form-item></div></section>

        <section class="detect-form-section">
          <div class="detect-form-section-title"><span>02</span><div><h3>{{ t('detect.detectionLogic') }}</h3><p>{{ t('detect.detectionLogicHint') }}</p></div></div>
          <template v-if="ruleForm.type === 'correlation'">
            <div class="condition-block">
              <div class="condition-block-head"><b>{{ t('detect.correlationSteps') }}</b><el-button size="small" plain @click="addStep">{{ t('detect.addStep') }}</el-button></div>
              <div v-for="(step, stepIndex) in ruleForm.steps" :key="stepIndex" class="condition-group">
                <div class="condition-group-head"><span>{{ t('detect.step') }} {{ stepIndex + 1 }}</span><el-button v-if="ruleForm.steps.length > 1" link type="danger" size="small" @click="ruleForm.steps.splice(stepIndex, 1)">{{ t('common.delete') }}</el-button></div>
                <FieldConditionBuilder v-model="ruleForm.steps[stepIndex]" :fields="fieldDefs" :reference-sets="referenceSets" :add-label="t('detect.addCondition')" :empty-hint="t('detect.noConditions')" :field-placeholder="t('detect.fieldPlaceholder')" :value-placeholder="t('detect.valuePlaceholder')" />
              </div>
              <EmptyState v-if="!ruleForm.steps.length" :title="t('detect.noSteps')" :description="t('detect.addStepHint')" />
            </div>
          </template>
          <template v-else>
            <div class="condition-block">
              <FieldConditionBuilder v-model="ruleForm.match" :title="t('detect.allConditions')" :add-label="t('detect.addCondition')" :empty-hint="t('detect.noConditions')" :fields="fieldDefs" :reference-sets="referenceSets" :field-placeholder="t('detect.fieldPlaceholder')" :value-placeholder="t('detect.valuePlaceholder')" />
            </div>
            <div class="condition-block">
              <div class="condition-block-head"><b>{{ t('detect.anyConditionGroup') }}</b><el-button size="small" plain @click="addMatchAnyGroup">{{ t('detect.addGroup') }}</el-button></div>
              <div v-for="(group, groupIndex) in ruleForm.matchAny" :key="groupIndex" class="condition-group">
                <div class="condition-group-head"><span>{{ t('detect.conditionGroup') }} {{ groupIndex + 1 }}</span><el-button link type="danger" size="small" @click="ruleForm.matchAny.splice(groupIndex, 1)">{{ t('common.delete') }}</el-button></div>
                <FieldConditionBuilder v-model="ruleForm.matchAny[groupIndex]" :fields="fieldDefs" :reference-sets="referenceSets" :add-label="t('detect.addCondition')" :empty-hint="t('detect.noConditions')" :field-placeholder="t('detect.fieldPlaceholder')" :value-placeholder="t('detect.valuePlaceholder')" />
              </div>
              <p v-if="!ruleForm.matchAny.length" class="form-hint">{{ t('detect.noAnyGroupHint') }}</p>
            </div>
          </template>
          <div v-if="fieldLoadError" class="form-hint">{{ t('detect.fieldCatalogFallback') }} · {{ fieldLoadError }}</div>
          <div class="detect-form-grid compact-grid">
            <el-form-item :label="t('detect.keyField')"><el-select v-model="ruleForm.keyField" filterable allow-create default-first-option clearable :placeholder="t('detect.fieldPlaceholder')"><el-option v-for="field in fieldDefs" :key="field.fieldName" :label="field.fieldName" :value="field.fieldName"><div class="field-option"><b>{{ field.fieldName }}</b><small>{{ field.fieldLabel || field.fieldType }} · {{ field.fieldType }}<span v-if="field.aggregatable"> · aggregate</span></small></div></el-option></el-select></el-form-item>
            <el-form-item :label="t('detect.threshold')"><el-input v-model.number="ruleForm.threshold" type="number" min="1" /></el-form-item>
            <el-form-item :label="t('detect.valueField')"><el-select v-model="ruleForm.valueField" filterable allow-create default-first-option clearable :placeholder="t('detect.fieldPlaceholder')"><el-option v-for="field in fieldDefs" :key="field.fieldName" :label="field.fieldName" :value="field.fieldName"><div class="field-option"><b>{{ field.fieldName }}</b><small>{{ field.fieldLabel || field.fieldType }} · {{ field.fieldType }}<span v-if="field.aggregatable"> · aggregate</span></small></div></el-option></el-select></el-form-item>
            <el-form-item :label="t('detect.minCount')"><el-input v-model.number="ruleForm.minCount" type="number" min="1" /></el-form-item>
          </div>
        </section>

        <section class="detect-form-section"><div class="detect-form-section-title"><span>03</span><div><h3>{{ t('detect.alertContent') }}</h3><p>{{ t('detect.alertContentHint') }}</p></div></div><div class="detect-form-grid"><el-form-item :label="t('detect.editor.alertTitle')"><el-input v-model="ruleForm.alertTitle" :placeholder="t('detect.editor.alertTitlePlaceholder')" /></el-form-item><el-form-item :label="t('detect.editor.alertDescription')"><el-input v-model="ruleForm.alertDescription" :placeholder="t('detect.editor.alertDescriptionPlaceholder')" /></el-form-item><el-form-item :label="t('detect.compatMessage')"><el-input v-model="ruleForm.message" /></el-form-item><el-form-item :label="t('detect.mitre')"><el-input v-model="ruleForm.mitre" placeholder="T1110" /></el-form-item></div><div class="condition-block"><FieldConditionBuilder v-model="ruleForm.whitelist" :title="t('detect.editor.whitelist')" :add-label="t('detect.editor.addWhitelist')" :empty-hint="t('detect.noWhitelistHint')" :fields="fieldDefs" :reference-sets="referenceSets" :field-placeholder="t('detect.fieldPlaceholder')" :value-placeholder="t('detect.valuePlaceholder')" /></div></section>

        <section class="detect-form-section"><div class="detect-form-section-title"><span>04</span><div><h3>{{ t('detect.advancedFields') }}</h3><p>{{ t('detect.advancedFieldsHint') }}</p></div></div><div v-if="ADVANCED_TYPES.includes(ruleForm.type) || rawOnlyRuleType" class="detect-advanced-warning"><b>{{ t('detect.advancedType') }}</b><span>{{ t('detect.advancedTypeHint') }}</span></div><div class="detect-form-grid compact-grid"><el-form-item :label="t('detect.routingField')"><el-input v-model="ruleForm.routingField" /></el-form-item><el-form-item :label="t('detect.warmup')"><el-input v-model.number="ruleForm.warmup" type="number" min="1" /></el-form-item><el-form-item :label="t('detect.baselineWindows')"><el-input v-model.number="ruleForm.baselineWindows" type="number" min="1" /></el-form-item><el-form-item :label="t('detect.sigma')"><el-input v-model.number="ruleForm.sigma" type="number" min="0" max="100" /></el-form-item><el-form-item :label="t('detect.ruleVersion')"><el-input v-model="ruleForm.version" /></el-form-item><el-form-item :label="t('detect.owner')"><el-input v-model="ruleForm.owner" /></el-form-item><el-form-item :label="t('detect.contentPack')"><el-input v-model="ruleForm.contentPack" /></el-form-item><el-form-item :label="t('detect.contentVersion')"><el-input v-model="ruleForm.contentVersion" /></el-form-item></div><details class="advanced-json"><summary>{{ t('detect.rawRuleJson') }}</summary><p>{{ t('detect.rawRuleJsonHint') }}</p><textarea v-model="advancedJson" rows="12" spellcheck="false" /><div v-if="advancedError" class="detect-feedback error">{{ advancedError }}</div><el-button size="small" @click="applyAdvancedJson">{{ t('detect.applyRawJson') }}</el-button></details></section>

        <section class="detect-form-section lifecycle-section"><div class="detect-form-section-title"><span>05</span><div><h3>{{ t('detect.testAndRelease') }}</h3><p>{{ t('detect.testAndReleaseHint') }}</p></div></div><div class="lifecycle-row"><div><span class="form-label">{{ t('detect.ruleStatus') }}</span><el-tag :type="statusTag(ruleForm.status)" size="small">{{ ruleForm.status }}</el-tag><span class="form-hint inline-hint">{{ ruleEditingId ? t('detect.lifecycleReadOnly') : t('detect.newRuleTesting') }}</span></div><div class="lifecycle-toggle"><span>{{ t('detect.executionToggle') }}</span><el-switch v-model="ruleForm.enabled" :disabled="!ruleEditingId || ruleForm.status !== 'ACTIVE'" /></div></div></section>
      </el-form>
      <div class="detect-editor-footer"><el-button @click="closeRuleEditor">{{ t('common.cancel') }}</el-button><el-button type="primary" :loading="saving" @click="saveRule">{{ t('common.save') }}</el-button></div>
    </section>

    <section class="detect-list-section"><div class="workspace-section-head list-head"><div><h2>{{ t('detect.rules') }}</h2><p>{{ t('detect.lifecycleHint') }}</p></div><span class="toolbar-count">{{ t('common.total', { total: rules.length }) }}</span></div><el-card shadow="never" class="detect-table-card"><el-table :data="rules" size="small" row-key="id"><el-table-column prop="name" :label="t('common.name')" min-width="180" show-overflow-tooltip /><el-table-column prop="type" :label="t('common.type')" width="150"><template #default="{ row }"><span>{{ typeLabel(row.type) }}</span></template></el-table-column><el-table-column prop="severity" :label="t('common.severity')" width="110"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column><el-table-column :label="t('detect.matchingConditions')" min-width="260" show-overflow-tooltip><template #default="{ row }"><span v-if="row.match?.length" class="mono">{{ row.match.map((condition: RuleCondition) => `${condition.field} ${condition.op} ${condition.value}`).join(' AND ') }}</span><span v-else-if="row.steps?.length">{{ t('detect.stepCount', { count: row.steps.length }) }}</span><span v-else>—</span></template></el-table-column><el-table-column :label="t('detect.ruleStatus')" width="110"><template #default="{ row }"><el-tag :type="statusTag(ruleStatus(row))" size="small">{{ ruleStatus(row) }}</el-tag></template></el-table-column><el-table-column :label="t('common.actions')" width="250" fixed="right"><template #default="{ row }"><el-button v-if="canManageRules" link type="primary" size="small" @click="openRuleEditor(row)">{{ t('common.edit') }}</el-button><el-button v-if="canManageRules && ['DRAFT', 'TESTING'].includes(ruleStatus(row))" link size="small" @click="testSingleRule(row)">{{ t('detect.testRule') }}</el-button><el-button v-if="canActivate && ruleStatus(row) === 'ACTIVE'" link size="small" @click="toggleRule(row)">{{ t('common.disable') }}</el-button><el-button v-if="canActivate && ['DISABLED', 'DRAFT', 'TESTING'].includes(ruleStatus(row))" link size="small" @click="toggleRule(row)">{{ t('common.enable') }}</el-button><el-button v-if="canManageRules && ruleStatus(row) !== 'ARCHIVED'" link size="small" @click="copyRuleAsDraft(row)">{{ t('common.copy') }}</el-button><el-button v-if="canManageRules && ['DRAFT', 'DISABLED'].includes(ruleStatus(row))" link type="danger" size="small" @click="removeRule(row)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table><EmptyState v-if="!loading && !rules.length" :title="t('detect.noRules')" :description="t('detect.noRulesHint')" /><div v-if="loading" class="detect-loading">{{ t('common.loading') }}</div></el-card></section>
  </div>
</template>

<style scoped>
.detect-view { display: flex; flex-direction: column; gap: 16px; }
.detect-stat-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.detect-stat { display: flex; flex-direction: column; gap: 8px; }.detect-stat span, .workspace-section-head p, .detect-form-section-title p, .form-hint { color: var(--ns-text-3); font-size: 12px; }.detect-stat b { color: var(--ns-text); font-size: 26px; line-height: 1; font-variant-numeric: tabular-nums; }.danger-text { color: var(--ns-danger) !important; }
.detect-feedback { display: flex; align-items: center; gap: 8px; padding: 9px 12px; border-radius: var(--ns-radius-md); font-size: 12px; }.detect-feedback span { overflow-wrap: anywhere; }.detect-feedback.error { color: var(--ns-danger); border: 1px solid color-mix(in srgb, var(--ns-danger) 28%, var(--ns-border)); background: color-mix(in srgb, var(--ns-danger) 7%, var(--ns-surface)); }
.detect-test-workspace, .detect-editor-workspace, .detect-list-section { min-width: 0; }.detect-test-workspace, .detect-editor-workspace { padding: 18px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-md); background: var(--ns-surface); }.workspace-section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }.workspace-section-head h2 { margin: 0; color: var(--ns-text); font-size: 17px; font-weight: 650; }.workspace-section-head p { margin: 5px 0 0; line-height: 1.5; }
.detect-test-grid { display: grid; grid-template-columns: minmax(360px, .9fr) minmax(0, 1.1fr); gap: 18px; }.detect-test-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; align-content: start; }.detect-test-form label, .lifecycle-row > div { display: flex; flex-direction: column; gap: 5px; color: var(--ns-text-2); font-size: 12px; }.detect-test-form label .el-input, .detect-test-form label .el-select { width: 100%; }.full-width { grid-column: 1 / -1; }.detect-test-actions { grid-column: 1 / -1; display: flex; align-items: center; gap: 10px; margin-top: 3px; }.detect-test-actions span { color: var(--ns-text-3); font-size: 11px; }.detect-test-result { min-width: 0; min-height: 270px; padding: 12px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-sm); background: var(--ns-bg-subtle); }.detect-test-result :deep(.empty-state) { padding: 42px 16px; }
.test-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; color: var(--ns-text-2); font-size: 12px; }.test-trace { margin-bottom: 8px; padding: 10px; border: 1px solid var(--ns-border); border-left: 3px solid var(--ns-info); border-radius: var(--ns-radius-sm); background: var(--ns-surface); }.test-trace.matched { border-left-color: var(--ns-success); }.test-trace.candidate { border-left-color: var(--ns-warning); }.test-trace-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.test-trace-head > div { min-width: 0; }.test-trace-head b, .test-trace-head .mono { display: block; }.test-trace-head b { color: var(--ns-text); }.test-trace-head .mono { margin-top: 2px; color: var(--ns-text-3); font-size: 10px; }.test-trace p { margin: 5px 0 8px; color: var(--ns-text-2); font-size: 11px; }.test-condition-list { display: grid; gap: 4px; }.test-condition { display: grid; grid-template-columns: 16px minmax(0, 1fr) minmax(55px, .5fr); gap: 5px; align-items: center; color: var(--ns-text-3); font-size: 10px; }.test-condition.matched { color: var(--ns-text-2); }.condition-mark { font-weight: 700; color: var(--ns-danger); }.test-condition.matched .condition-mark { color: var(--ns-success); }.test-condition .mono { overflow-wrap: anywhere; }.test-no-condition { color: var(--ns-text-3); font-size: 11px; }
.detect-editor-workspace { padding-bottom: 0; }.workspace-section-actions { display: flex; align-items: center; gap: 8px; }.detect-editor-form { display: flex; flex-direction: column; gap: 12px; }.detect-form-section { padding: 16px 0; border-top: 1px solid var(--ns-border); }.detect-form-section:first-child { border-top: 0; padding-top: 0; }.detect-form-section-title { display: flex; gap: 10px; margin-bottom: 14px; }.detect-form-section-title > span { color: var(--ns-accent-fg); font-family: var(--ns-font-mono); font-size: 11px; font-weight: 700; }.detect-form-section-title h3 { margin: 0; color: var(--ns-text); font-size: 14px; font-weight: 650; }.detect-form-section-title p { margin: 4px 0 0; }.detect-form-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px 12px; }.compact-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }.detect-form-grid :deep(.el-form-item) { margin-bottom: 0; }.detect-form-grid :deep(.el-select), .detect-form-grid :deep(.el-input) { width: 100%; }.condition-block { margin-top: 10px; padding: 11px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-sm); background: var(--ns-bg-subtle); }.condition-block-head, .condition-group-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }.condition-block-head b { color: var(--ns-text-2); font-size: 12px; }.condition-group { margin-top: 8px; padding: 9px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-sm); background: var(--ns-surface); }.condition-group-head { margin-bottom: 6px; color: var(--ns-text-3); font-size: 11px; }.condition-row { display: grid; grid-template-columns: minmax(140px, .85fr) 130px minmax(140px, 1fr) auto; gap: 6px; align-items: center; margin-bottom: 6px; }.condition-row :deep(.el-input), .condition-row :deep(.el-select) { width: 100%; }.form-hint { margin: 8px 0 0; line-height: 1.5; }.inline-hint { margin-left: 8px; }.detect-advanced-warning { display: flex; gap: 8px; align-items: baseline; margin-bottom: 12px; padding: 9px 11px; border: 1px solid color-mix(in srgb, var(--ns-warning) 30%, var(--ns-border)); border-radius: var(--ns-radius-sm); background: color-mix(in srgb, var(--ns-warning) 8%, var(--ns-surface)); font-size: 12px; }.detect-advanced-warning b { color: var(--ns-warning); }.detect-advanced-warning span { color: var(--ns-text-2); }.advanced-json { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--ns-border); }.advanced-json summary { color: var(--ns-text-2); cursor: pointer; font-size: 12px; font-weight: 600; }.advanced-json p { color: var(--ns-text-3); font-size: 11px; }.advanced-json textarea { display: block; width: 100%; box-sizing: border-box; margin: 8px 0; padding: 10px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-sm); background: var(--ns-bg-inset); color: var(--ns-text); font: 11px/1.5 var(--ns-font-mono); resize: vertical; }.lifecycle-section { padding-bottom: 18px; }.lifecycle-row { display: flex; justify-content: space-between; gap: 16px; align-items: center; }.lifecycle-row > div { flex-direction: row; align-items: center; }.form-label { color: var(--ns-text-2); font-size: 12px; }.lifecycle-toggle { white-space: nowrap; }.detect-editor-footer { display: flex; justify-content: flex-end; gap: 8px; padding: 14px 0 0; border-top: 1px solid var(--ns-border); }.detect-list-section { padding-top: 2px; }.list-head { align-items: center; margin-bottom: 10px; }.toolbar-count { color: var(--ns-text-3); font-size: 12px; }.detect-table-card .el-card__body { padding: 0; }.detect-loading { padding: 20px; color: var(--ns-text-3); text-align: center; font-size: 12px; }
.field-option { display: flex; flex-direction: column; gap: 2px; line-height: 1.25; }.field-option small { color: var(--ns-text-3); font-size: 10px; }
@media (max-width: 1000px) { .detect-stat-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.detect-test-grid { grid-template-columns: 1fr; }.detect-form-grid, .compact-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .detect-stat-grid, .detect-test-form, .detect-form-grid, .compact-grid { grid-template-columns: 1fr; }.full-width { grid-column: auto; }.condition-row { grid-template-columns: 1fr; }.lifecycle-row { align-items: flex-start; flex-direction: column; }.lifecycle-row > div { align-items: flex-start; flex-direction: column; }.workspace-section-head { flex-direction: column; }.workspace-section-actions { width: 100%; justify-content: space-between; } }
</style>
