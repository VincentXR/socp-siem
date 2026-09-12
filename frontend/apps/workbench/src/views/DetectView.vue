<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { watch } from 'vue'
import { useUnsavedChanges } from '../composables/useUnsavedChanges'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
const route = useRoute()
const router = useRouter()
const showTest = ref(false)
const ruleKeyword = ref('')

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
  listFields, listRefSets, testGasRules,
  listTechniques,
  type DetectionIngestEvent, type FieldDef, type GasStats, type LateEventPolicy, type ReferenceSet, type RuleCondition, type RuleSpec, type Technique,
} from '../api'
import { useI18n } from '../composables/useI18n'
import { traceRuleConditions } from '../lib/detection-test'

const { t } = useI18n()
const state = inject(WORKBENCH_STATE, null)
const currentRole = computed(() => state?.currentRole.value ?? '')
const canActivate = computed(() => ['admin', 'role_admin'].includes(currentRole.value.toLowerCase()))
const canManageRules = computed(() => ['admin', 'role_admin', 'analyst', 'role_analyst'].includes(currentRole.value.toLowerCase()))
type RuleEditorForm = {
  id: string; name: string; type: string; severity: string; message: string
  alertTitle: string; alertDescription: string; enabled: boolean; status: string
  window: string; keyField: string; groupBy: string; routingField: string
  lateAllowedLateness: string; lateHandling: 'DROP' | 'ACCEPT'; threshold: number | null
  valueField: string; warmup: number | null; baselineWindows: number | null; sigma: number | null
  minCount: number | null; mitre: string; version: string; owner: string
  contentPack: string; contentVersion: string; match: RuleCondition[]; matchAny: RuleCondition[][]
  whitelist: RuleCondition[]; steps: RuleCondition[][]
}

type TestConditionTrace = { condition: RuleCondition; matched: boolean; observed: string; scope?: string }
type RuleTestTrace = {
  id: string; name: string; type: string; state: 'MATCHED' | 'CANDIDATE' | 'NO_MATCH'
  reason: string; conditions: TestConditionTrace[]
}
type RuleTestResult = { checked: number; matched: number; candidates: number; traces: RuleTestTrace[] }

const RULE_TYPES = ['pattern', 'threshold', 'correlation', 'correlation-set', 'baseline', 'rare']
const ADVANCED_TYPES = ['correlation-set', 'baseline', 'rare']
const STATEFUL_TYPES = ['threshold', 'correlation', 'correlation-set', 'baseline', 'rare']

const allRules = ref<RuleSpec[]>([])
const fieldDefs = ref<FieldDef[]>([])
const referenceSets = ref<ReferenceSet[]>([])
const techniques = ref<Technique[]>([])
const gasStat = ref<GasStats>({ rules: 0, eventCount: 0, alertCount: 0, dropCount: 0, suppressedCount: 0, queueLoad: 0 })
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const fieldLoadError = ref('')
const referenceLoadError = ref('')
const techniqueLoadError = ref('')
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
const testing = ref(false)
const sampleEventsText = ref('')
const testResult = ref<RuleTestResult | null>(null)

function emptyCondition(): RuleCondition { return { field: 'msg', op: 'contains', value: '' } }
function clone<T>(value: T): T { return value == null ? value : JSON.parse(JSON.stringify(value)) as T }
function cloneConditionGroups(groups: RuleCondition[][] | undefined): RuleCondition[][] { return groups?.length ? clone(groups) : [] }

function emptyRuleForm(): RuleEditorForm {
  return {
    id: '', name: '', type: 'pattern', severity: 'HIGH', message: '', alertTitle: '', alertDescription: '', enabled: false,
    status: 'DRAFT', window: '60s', keyField: 'src_ip', groupBy: 'src_ip', routingField: 'src_ip',
    lateAllowedLateness: '60s', lateHandling: 'DROP', threshold: 5, valueField: '', warmup: null,
    baselineWindows: null, sigma: null, minCount: null, mitre: '', version: '', owner: '', contentPack: '', contentVersion: '',
    match: [emptyCondition()], matchAny: [], whitelist: [], steps: [],
  }
}

function textValue(value: unknown): string { return value == null ? '' : String(value) }
function numberValue(value: unknown): number | null { if (value == null || value === '') return null; const parsed = Number(value); return Number.isFinite(parsed) ? parsed : null }

function formFromRule(rule: RuleSpec): RuleEditorForm {
  const policy = rule.lateEventPolicy && typeof rule.lateEventPolicy === 'object'
    ? rule.lateEventPolicy as LateEventPolicy
    : undefined
  const groupBy = textValue(rule.groupBy || rule.keyField || rule.routingField)
  return {
    id: textValue(rule.id), name: textValue(rule.name), type: textValue(rule.type || 'pattern'), severity: textValue(rule.severity || 'HIGH'),
    message: textValue(rule.message || rule.alert?.description), alertTitle: textValue(rule.alert?.title || rule.name),
    alertDescription: textValue(rule.alert?.description || rule.message), enabled: Boolean(rule.enabled),
    status: textValue(rule.status || (rule.enabled ? 'ACTIVE' : 'DRAFT')).toUpperCase(), window: textValue(rule.window),
    keyField: groupBy, groupBy, routingField: groupBy,
    lateAllowedLateness: textValue(policy?.allowedLateness || rule.window),
    lateHandling: String(policy?.handling || 'DROP').toUpperCase() === 'ACCEPT' ? 'ACCEPT' : 'DROP',
    threshold: numberValue(rule.threshold),
    valueField: textValue(rule.valueField), warmup: numberValue(rule.warmup), baselineWindows: numberValue(rule.baselineWindows),
    sigma: numberValue(rule.sigma), minCount: numberValue(rule.minCount), mitre: textValue(rule.mitre), version: textValue(rule.version),
    owner: textValue(rule.owner), contentPack: textValue(rule.contentPack), contentVersion: textValue(rule.contentVersion),
    match: clone(rule.match ?? []), matchAny: cloneConditionGroups(rule.matchAny), whitelist: clone(rule.whitelist ?? (rule.allowlist as RuleCondition[] | undefined) ?? []), steps: clone(rule.steps ?? []),
  }
}

const ruleForm = ref<RuleEditorForm>(emptyRuleForm())
const ownerOptions = computed(() => {
  const values = new Set(state?.operatorOptions?.value ?? [])
  const current = ruleForm.value.owner.trim()
  if (current) values.add(current)
  return [...values].filter(Boolean)
})
const changes = useUnsavedChanges(() => ({ form: ruleForm.value, json: advancedJson.value }), () => showRuleEditor.value)
const rawOnlyRuleType = computed(() => Boolean(ruleForm.value.type) && !RULE_TYPES.includes(ruleForm.value.type))
const visibleRules = computed(() => ruleStatusFilter.value
  ? allRules.value.filter(rule => ruleStatus(rule) === ruleStatusFilter.value)
  : allRules.value)
const rules = computed(() => visibleRules.value.filter(rule => `${rule.name} ${rule.type}`.toLowerCase().includes(ruleKeyword.value.toLowerCase())))

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
  const [ruleResult, statResult, fieldResult, refsetResult, techniqueResult] = await Promise.allSettled([listRules(), gasStats(), listFields(), listRefSets(), listTechniques()])
  if (ruleResult.status === 'fulfilled') allRules.value = ruleResult.value.map(normalizeRuleSpec).filter((rule): rule is RuleSpec => rule !== null)
  else loadError.value = ruleResult.reason instanceof Error ? ruleResult.reason.message : String(ruleResult.reason)
  if (statResult.status === 'fulfilled') gasStat.value = statResult.value
  if (fieldResult.status === 'fulfilled') {
    fieldDefs.value = fieldResult.value
    fieldLoadError.value = ''
  } else {
    fieldLoadError.value = fieldResult.reason instanceof Error ? fieldResult.reason.message : String(fieldResult.reason)
  }
  if (refsetResult.status === 'fulfilled') {
    referenceSets.value = refsetResult.value
    referenceLoadError.value = ''
  } else {
    referenceLoadError.value = refsetResult.reason instanceof Error ? refsetResult.reason.message : String(refsetResult.reason)
  }
  if (techniqueResult.status === 'fulfilled') {
    techniques.value = techniqueResult.value
    techniqueLoadError.value = ''
  } else {
    techniqueLoadError.value = techniqueResult.reason instanceof Error ? techniqueResult.reason.message : String(techniqueResult.reason)
  }
  loading.value = false
}

function onGroupByChange(value: unknown): void {
  const selected = textValue(value).trim()
  ruleForm.value.groupBy = selected
  ruleForm.value.keyField = selected
  // Stateful aggregation and Kafka routing must use the same dimension. The
  // routing field is therefore derived from the selected key instead of a
  // second free-text value that can silently diverge.
  ruleForm.value.routingField = selected
}

function onKeyFieldChange(value: unknown): void { onGroupByChange(value) }

function openRuleEditor(row?: unknown): void {
  if (!canManageRules.value) return
  const rule = row ? normalizeRuleSpec(row) : null
  void router.push(rule ? { name: 'rule-edit', params: { ruleId: String(rule.id) } } : { name: 'rule-new' })
}
async function syncEditorRoute() {
  if (!route.meta.editor) { showRuleEditor.value = false; return }
  const id = String(route.params.ruleId || route.query.copy || '')
  if (!canManageRules.value && (!id || Boolean(route.query.copy))) {
    showRuleEditor.value = false
    await router.replace({ name: 'detect' })
    return
  }
  const rule = id ? allRules.value.find(item => String(item.id) === id) : null
  if (id && !rule) { loadError.value = 'Rule not found: ' + id; showRuleEditor.value = false; return }
  ruleEditingId.value = rule && !route.query.copy ? String(rule.id) : null
  sourceRule.value = rule ? clone(rule) : null
  ruleForm.value = rule ? formFromRule(rule) : emptyRuleForm()
  if (route.query.copy && sourceRule.value) {
    delete (sourceRule.value as Partial<RuleSpec>).id
    ruleForm.value.id = ''
    ruleForm.value.name += ' · copy'
    ruleForm.value.status = 'DRAFT'
    ruleForm.value.enabled = false
  }
  advancedJson.value = JSON.stringify(sourceRule.value ?? {}, null, 2)
  saveError.value = ''; advancedError.value = ''
  showRuleEditor.value = true
  changes.markSaved()
}
async function closeRuleEditor(): Promise<void> {
  if (saving.value) return
  await router.push({ name: 'detect' })
}

function cleanConditions(conditions: RuleCondition[]): RuleCondition[] {
  return conditions.map(condition => {
    if (!condition.field?.trim() || !condition.op?.trim() || !condition.value?.trim()) throw new Error(t('forms.required'))
    // Literal values can contain meaningful spaces; nested metadata belongs to
    // the condition and must survive a visual edit or an advanced JSON edit.
    return { ...clone(condition), field: condition.field.trim(), op: condition.op.trim(), value: condition.value }
  })
}
function cleanGroups(groups: RuleCondition[][]): RuleCondition[][] {
  return groups.map(group => {
    if (!group.length) throw new Error(t('forms.required'))
    return cleanConditions(group)
  })
}
function setOptional(target: Record<string, unknown>, key: string, value: unknown): void {
  if (value === null || value === undefined || (typeof value === 'string' && !value.trim())) delete target[key]
  else target[key] = value
}

function buildRuleSpec(): Partial<RuleSpec> {
  const spec = (sourceRule.value ? clone(sourceRule.value) : {}) as Record<string, unknown>
  spec.name = ruleForm.value.name.trim(); spec.type = ruleForm.value.type; spec.severity = ruleForm.value.severity
  setOptional(spec, 'message', ruleForm.value.message); setOptional(spec, 'window', ruleForm.value.window); setOptional(spec, 'keyField', ruleForm.value.keyField)
  setOptional(spec, 'groupBy', ruleForm.value.groupBy)
  setOptional(spec, 'routingField', ruleForm.value.routingField); setOptional(spec, 'threshold', ruleForm.value.threshold); setOptional(spec, 'valueField', ruleForm.value.valueField)
  setOptional(spec, 'warmup', ruleForm.value.warmup); setOptional(spec, 'baselineWindows', ruleForm.value.baselineWindows); setOptional(spec, 'sigma', ruleForm.value.sigma)
  setOptional(spec, 'minCount', ruleForm.value.minCount); setOptional(spec, 'mitre', ruleForm.value.mitre); setOptional(spec, 'version', ruleForm.value.version)
  setOptional(spec, 'owner', ruleForm.value.owner); setOptional(spec, 'contentPack', ruleForm.value.contentPack); setOptional(spec, 'contentVersion', ruleForm.value.contentVersion)
  const rawPolicy = spec.lateEventPolicy
  const policy = rawPolicy && typeof rawPolicy === 'object' && !Array.isArray(rawPolicy)
    ? clone(rawPolicy) as Record<string, unknown>
    : {}
  if (STATEFUL_TYPES.includes(ruleForm.value.type) || Object.keys(policy).length) {
    setOptional(policy, 'allowedLateness', ruleForm.value.lateAllowedLateness)
    setOptional(policy, 'handling', ruleForm.value.lateHandling)
    if (Object.keys(policy).length) spec.lateEventPolicy = policy
    else delete spec.lateEventPolicy
  }
  const alert = (spec.alert && typeof spec.alert === 'object' ? clone(spec.alert) : {}) as Record<string, unknown>
  setOptional(alert, 'title', ruleForm.value.alertTitle); setOptional(alert, 'description', ruleForm.value.alertDescription)
  if (Object.keys(alert).length) spec.alert = alert; else delete spec.alert
  spec.match = cleanConditions(ruleForm.value.match); spec.matchAny = cleanGroups(ruleForm.value.matchAny); spec.steps = cleanGroups(ruleForm.value.steps); spec.whitelist = cleanConditions(ruleForm.value.whitelist)
  delete spec.allowlist

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
  if (!canManageRules.value) return
  saveError.value = ''
  if (!ruleForm.value.name.trim()) { saveError.value = t('detect.nameRequired'); return }
  saving.value = true
  try {
    const spec = buildRuleSpec()
    const saved = ruleEditingId.value ? await updateGasRule(ruleEditingId.value, spec) : await createGasRule(spec)
    const normalized = normalizeRuleSpec(saved)
    if (normalized) { ruleEditingId.value = String(normalized.id); sourceRule.value = clone(normalized); ruleForm.value = formFromRule(normalized); advancedJson.value = JSON.stringify(normalized, null, 2) }
    changes.markSaved()
    await loadRules()
    if (ruleEditingId.value) await router.replace({ name: 'rule-edit', params: { ruleId: ruleEditingId.value } })
  } catch (error) {
    saveError.value = t('detect.saveFailed', { message: error instanceof Error ? error.message : String(error) })
  } finally { saving.value = false }
}

function applyAdvancedJson(): void {
  if (!canManageRules.value) return
  advancedError.value = ''
  try {
    const parsed = JSON.parse(advancedJson.value) as unknown
    const normalized = normalizeRuleSpec(parsed)
    if (!normalized) throw new Error(t('detect.invalidRuleJson'))
    sourceRule.value = clone(normalized); ruleForm.value = formFromRule(normalized); advancedJson.value = JSON.stringify(normalized, null, 2)
  } catch (error) { advancedError.value = error instanceof Error ? error.message : String(error) }
}

function ruleUpdateSpec(rule: RuleSpec, enabled: boolean, status: string): Partial<RuleSpec> {
  const spec = clone(rule) as Partial<RuleSpec>; spec.id = String(rule.id); spec.enabled = enabled; spec.status = status; return spec
}

async function toggleRule(row: unknown): Promise<void> {
  if (!canManageRules.value) return
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
  if (!canManageRules.value) return
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
  if (!canManageRules.value) return
  const rule = normalizeRuleSpec(row)
  if (rule) void router.push({ name: 'rule-new', query: { copy: String(rule.id) } })
}

function testSingleRule(row: unknown): void {
  if (!canManageRules.value) return
  const rule = normalizeRuleSpec(row)
  if (!rule) return
  testRuleId.value = String(rule.id)
  showTest.value = true
}

function addMatchAnyGroup(): void { if (canManageRules.value) ruleForm.value.matchAny.push([emptyCondition()]) }
function addStep(): void { if (canManageRules.value) ruleForm.value.steps.push([emptyCondition()]) }
function ruleStatus(row: unknown): string {
  const rule = row as Partial<RuleSpec>
  return textValue(rule.status).toUpperCase() || (rule.enabled ? 'ACTIVE' : 'DRAFT')
}
function statusTag(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'ACTIVE') return 'success'; if (status === 'TESTING') return 'warning'; if (status === 'DISABLED') return 'info'; if (status === 'ARCHIVED') return 'danger'; return 'primary'
}
function lifecycleStatusLabel(status: string): string {
  const value = String(status ?? '')
  if (!value) return ''
  const key = 'detect.status.' + value
  const translated = t(key)
  return translated === key ? value : translated
}
function typeLabel(type: string): string {
  const value = String(type ?? '')
  if (!value) return ''
  const key = 'detect.ruleTypes.' + value
  const translated = t(key)
  if (translated !== key) return translated
  return ADVANCED_TYPES.includes(value) ? `${value} · ${t('detect.advancedType')}` : value
}
function testStateLabel(state: RuleTestTrace['state']): string {
  const key = 'detect.testStates.' + state
  const translated = t(key)
  return translated === key ? state : translated
}
function conditionScopeLabel(scope?: string): string {
  if (!scope) return ''
  if (scope === 'match') return t('detect.allConditions')
  if (scope === 'whitelist') return t('detect.editor.whitelist')
  const group = /^(matchAny|step) (\d+)$/.exec(scope)
  if (!group) return scope
  return group[1] === 'step'
    ? `${t('detect.step')} ${group[2]}`
    : `${t('detect.anyConditionGroup')} ${group[2]}`
}

async function runIsolatedTest(): Promise<void> {
  if (!canManageRules.value || testing.value) return
  testError.value = ''; testResult.value = null; testing.value = true
  try {
    const parsed = JSON.parse(testInput.value.fieldsText) as unknown
    if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error(t('detect.fieldsObjectRequired'))
    const fields = Object.fromEntries(Object.entries(parsed).map(([key, value]) => [key, typeof value === 'string' ? value : JSON.stringify(value)]))
    const events: DetectionIngestEvent[] = sampleEventsText.value.trim() ? JSON.parse(sampleEventsText.value) : [{
      source: testInput.value.source, host: testInput.value.host, severity: testInput.value.severity,
      msg: testInput.value.message, fields,
    }]
    if (!Array.isArray(events) || !events.length || events.length > 100) throw new Error(t('detect.sampleLimit'))
    const selected = showRuleEditor.value ? [{ ...buildRuleSpec(), id: ruleEditingId.value || 'dry-run-draft' }]
      : testRuleId.value ? rules.value.filter(rule => String(rule.id) === testRuleId.value) : rules.value
    if (!selected.length || selected.length > 20) throw new Error(t('detect.ruleTestLimit'))
    const result = await testGasRules(selected, events)
    const selectedById = new Map(selected.map(rule => [String(rule.id), rule]))
    const traces: RuleTestTrace[] = result.map(row => {
      const sourceRule = selectedById.get(String(row.id)) ?? selected.find(rule => rule.name === row.name)
      const conditionTrace = sourceRule ? traceRuleConditions(sourceRule, events) : { conditions: [], hasConditionMatch: false, whitelistMatched: false }
      const candidate = !row.matched && !conditionTrace.whitelistMatched && ADVANCED_TYPES.includes(String(sourceRule?.type)) && conditionTrace.hasConditionMatch
      const state: RuleTestTrace['state'] = row.matched ? 'MATCHED' : candidate ? 'CANDIDATE' : 'NO_MATCH'
      const reasonKey = conditionTrace.whitelistMatched
        ? 'detect.testWhitelisted'
        : state === 'MATCHED' ? 'detect.testMatched' : state === 'CANDIDATE' ? 'detect.testCandidate' : 'detect.testNoMatch'
      return {
        id: row.id, name: row.name, type: row.type, state,
        reason: `${t(reasonKey)} · ${row.eventCount} ${t('detect.sampleEvents')} · ${row.alerts.length} ${t('detect.alarmsCount')}`,
        conditions: conditionTrace.conditions,
      }
    })
    testResult.value = { checked: traces.length, matched: traces.filter(trace => trace.state === 'MATCHED').length, candidates: traces.filter(trace => trace.state === 'CANDIDATE').length, traces }
  } catch (error) { testError.value = error instanceof Error ? error.message : String(error) }
  finally { testing.value = false }
}

watch(() => route.fullPath, () => { void syncEditorRoute() })
onMounted(async () => { await loadRules(); await syncEditorRoute() })
</script>

<template>
  <div class="page-pad view-enter detect-view">
    <PageHeader :eyebrow="t('menuGroup.detectAndResponse')" :title="t('detect.title')" :description="t('detect.workspaceDescription')">
      <template #actions><el-button v-if="showRuleEditor" @click="closeRuleEditor">{{ t('forms.back') }}</el-button><el-select v-if="!showRuleEditor" v-model="ruleStatusFilter" size="small" clearable :placeholder="t('common.filter')" style="width:150px"><el-option v-for="status in ['DRAFT', 'TESTING', 'ACTIVE', 'DISABLED', 'ARCHIVED']" :key="status" :label="lifecycleStatusLabel(status)" :value="status" /></el-select><el-button size="small" :loading="loading" @click="loadRules">{{ t('common.refresh') }}</el-button><el-button v-if="canManageRules && !showRuleEditor" type="primary" size="small" @click="openRuleEditor()">{{ t('detect.createRule') }}</el-button></template>
    </PageHeader>
    <div v-if="showRuleEditor && !canManageRules" class="page-readonly-hint">{{ t('detect.readOnly') }}</div>

    <div v-if="referenceLoadError" class="detect-feedback error" role="alert"><strong>{{ t('menu.refset') }}</strong><span>{{ referenceLoadError }}</span><el-button size="small" :loading="loading" @click="loadRules">{{ t('common.refresh') }}</el-button></div>
    <div v-if="loadError" class="detect-feedback error" role="alert"><strong>{{ t('detect.loadFailed') }}</strong><span>{{ loadError }}</span><el-button size="small" @click="loadRules">{{ t('common.refresh') }}</el-button></div>
    <div v-if="actionMessage" class="detect-feedback error" role="alert">{{ actionMessage }}</div>

    <div v-if="!showRuleEditor" class="detect-stat-grid">
      <el-card shadow="never"><div class="detect-stat"><span>{{ t('detect.rulesCount') }}</span><b>{{ gasStat.rules ?? 0 }}</b></div></el-card>
      <el-card shadow="never"><div class="detect-stat"><span>{{ t('detect.eventsCount') }}</span><b>{{ gasStat.eventCount ?? 0 }}</b></div></el-card>
      <el-card shadow="never"><div class="detect-stat"><span>{{ t('detect.alarmsCount') }}</span><b class="danger-text">{{ gasStat.alertCount ?? 0 }}</b></div></el-card>
      <el-card shadow="never"><div class="detect-stat"><span>{{ t('detect.queueLoad') }}</span><b>{{ (gasStat.queueLoad * 100).toFixed(0) }}%</b></div></el-card>
    </div>


    <section v-if="showRuleEditor" class="detect-editor-workspace" :class="{ 'detect-editor-readonly': !canManageRules }">
      <div class="workspace-section-head"><div><div class="page-eyebrow">{{ t('detect.editorEyebrow') }}</div><h2>{{ ruleEditingId ? t('detect.editor.editRule') : t('detect.createRule') }}</h2><p>{{ t('detect.editorHint') }}</p></div><div class="workspace-section-actions"><el-tag v-if="ruleEditingId" :type="statusTag(ruleForm.status)" size="small">{{ lifecycleStatusLabel(ruleForm.status) }}</el-tag><el-button size="small" @click="closeRuleEditor">{{ t('common.cancel') }}</el-button></div></div>
      <div v-if="saveError" class="detect-feedback error" role="alert">{{ saveError }}</div>
      <el-form label-position="top" class="detect-editor-form" :disabled="!canManageRules">
        <section class="detect-form-section"><div class="detect-form-section-title"><span>01</span><div><h3>{{ t('detect.dataScope') }}</h3><p>{{ t('detect.dataScopeHint') }}</p></div></div><div class="detect-form-grid"><el-form-item :label="t('common.name')" required><el-input v-model="ruleForm.name" :placeholder="t('detect.editor.namePlaceholder')" /></el-form-item><el-form-item :label="t('common.type')"><el-select v-model="ruleForm.type"><el-option v-if="rawOnlyRuleType" :label="typeLabel(ruleForm.type)" :value="ruleForm.type" /><el-option v-for="type in RULE_TYPES" :key="type" :label="typeLabel(type)" :value="type" /></el-select></el-form-item><el-form-item :label="t('common.severity')"><el-select v-model="ruleForm.severity"><el-option v-for="severity in SEVERITIES" :key="severity" :label="t('severities.' + severity) || severity" :value="severity" /></el-select></el-form-item><el-form-item :label="t('detect.editor.window')"><el-input v-model="ruleForm.window" :placeholder="t('detect.editor.windowPlaceholder')" /></el-form-item></div></section>

        <section class="detect-form-section">
          <div class="detect-form-section-title"><span>02</span><div><h3>{{ t('detect.detectionLogic') }}</h3><p>{{ t('detect.detectionLogicHint') }}</p></div></div>
          <template v-if="ruleForm.type === 'correlation'">
            <div class="condition-block">
              <div class="condition-block-head"><b>{{ t('detect.correlationSteps') }}</b><el-button v-if="canManageRules" size="small" plain @click="addStep">{{ t('detect.addStep') }}</el-button></div>
              <div v-for="(step, stepIndex) in ruleForm.steps" :key="stepIndex" class="condition-group">
                <div class="condition-group-head"><span>{{ t('detect.step') }} {{ stepIndex + 1 }}</span><el-button v-if="canManageRules && ruleForm.steps.length > 1" link type="danger" size="small" @click="ruleForm.steps.splice(stepIndex, 1)">{{ t('common.delete') }}</el-button></div>
                <FieldConditionBuilder v-model="ruleForm.steps[stepIndex]" :read-only="!canManageRules" :fields="fieldDefs" :reference-sets="referenceSets" :add-label="t('detect.addCondition')" :empty-hint="t('detect.noConditions')" :field-placeholder="t('detect.fieldPlaceholder')" :value-placeholder="t('detect.valuePlaceholder')" />
              </div>
              <EmptyState v-if="!ruleForm.steps.length" :title="t('detect.noSteps')" :description="t('detect.addStepHint')" />
            </div>
          </template>
          <template v-else>
            <div class="condition-block">
              <FieldConditionBuilder v-model="ruleForm.match" :read-only="!canManageRules" :title="t('detect.allConditions')" :add-label="t('detect.addCondition')" :empty-hint="t('detect.noConditions')" :fields="fieldDefs" :reference-sets="referenceSets" :field-placeholder="t('detect.fieldPlaceholder')" :value-placeholder="t('detect.valuePlaceholder')" />
            </div>
            <div class="condition-block">
              <div class="condition-block-head"><b>{{ t('detect.anyConditionGroup') }}</b><el-button v-if="canManageRules" size="small" plain @click="addMatchAnyGroup">{{ t('detect.addGroup') }}</el-button></div>
              <div v-for="(group, groupIndex) in ruleForm.matchAny" :key="groupIndex" class="condition-group">
                <div class="condition-group-head"><span>{{ t('detect.conditionGroup') }} {{ groupIndex + 1 }}</span><el-button v-if="canManageRules" link type="danger" size="small" @click="ruleForm.matchAny.splice(groupIndex, 1)">{{ t('common.delete') }}</el-button></div>
                <FieldConditionBuilder v-model="ruleForm.matchAny[groupIndex]" :read-only="!canManageRules" :fields="fieldDefs" :reference-sets="referenceSets" :add-label="t('detect.addCondition')" :empty-hint="t('detect.noConditions')" :field-placeholder="t('detect.fieldPlaceholder')" :value-placeholder="t('detect.valuePlaceholder')" />
              </div>
              <p v-if="!ruleForm.matchAny.length" class="form-hint">{{ t('detect.noAnyGroupHint') }}</p>
            </div>
          </template>
          <div v-if="fieldLoadError" class="form-hint">{{ t('detect.fieldCatalogFallback') }} · {{ fieldLoadError }}</div>
          <div class="detect-form-grid compact-grid">
            <el-form-item :label="t('detect.groupBy')"><el-select v-model="ruleForm.groupBy" filterable default-first-option clearable :placeholder="t('detect.fieldPlaceholder')" @change="onGroupByChange"><el-option v-if="ruleForm.groupBy && !fieldDefs.some(field => field.fieldName === ruleForm.groupBy)" :label="ruleForm.groupBy" :value="ruleForm.groupBy" /><el-option v-for="field in fieldDefs" :key="field.fieldName" :label="field.fieldName" :value="field.fieldName"><div class="field-option"><b>{{ field.fieldName }}</b><small>{{ field.fieldLabel || field.fieldType }} · {{ field.fieldType }}<span v-if="field.aggregatable"> · aggregate</span></small></div></el-option></el-select><span class="form-hint">{{ t('detect.groupByHint') }}</span></el-form-item>
            <el-form-item v-if="['threshold', 'correlation-set'].includes(ruleForm.type)" :label="t('detect.threshold')"><el-input v-model.number="ruleForm.threshold" type="number" min="1" /></el-form-item>
            <el-form-item v-if="['baseline', 'rare'].includes(ruleForm.type)" :label="t('detect.valueField')"><el-select v-model="ruleForm.valueField" filterable default-first-option clearable :placeholder="t('detect.fieldPlaceholder')"><el-option v-if="ruleForm.valueField && !fieldDefs.some(field => field.fieldName === ruleForm.valueField)" :label="ruleForm.valueField" :value="ruleForm.valueField" /><el-option v-for="field in fieldDefs" :key="field.fieldName" :label="field.fieldName" :value="field.fieldName"><div class="field-option"><b>{{ field.fieldName }}</b><small>{{ field.fieldLabel || field.fieldType }} · {{ field.fieldType }}<span v-if="field.aggregatable"> · aggregate</span></small></div></el-option></el-select></el-form-item>
            <el-form-item v-if="['baseline', 'rare'].includes(ruleForm.type)" :label="t('detect.minCount')"><el-input v-model.number="ruleForm.minCount" type="number" min="1" /></el-form-item>
          </div>
        </section>

        <section class="detect-form-section"><div class="detect-form-section-title"><span>03</span><div><h3>{{ t('detect.alertContent') }}</h3><p>{{ t('detect.alertContentHint') }}</p></div></div><div class="detect-form-grid"><el-form-item :label="t('detect.editor.alertTitle')"><el-input v-model="ruleForm.alertTitle" :placeholder="t('detect.editor.alertTitlePlaceholder')" /></el-form-item><el-form-item :label="t('detect.editor.alertDescription')"><el-input v-model="ruleForm.alertDescription" :placeholder="t('detect.editor.alertDescriptionPlaceholder')" /></el-form-item><el-form-item :label="t('detect.compatMessage')"><el-input v-model="ruleForm.message" /></el-form-item><el-form-item :label="t('detect.mitre')"><el-select v-model="ruleForm.mitre" filterable default-first-option clearable placeholder="T1110"><el-option v-if="ruleForm.mitre && !techniques.some(item => item.id === ruleForm.mitre)" :label="ruleForm.mitre + ' (custom)'" :value="ruleForm.mitre" /><el-option v-for="technique in techniques" :key="technique.id" :label="`${technique.id} · ${technique.name}`" :value="technique.id" /></el-select><span v-if="techniqueLoadError" class="form-hint">{{ t('detect.fieldCatalogFallback') }}</span></el-form-item></div><div class="condition-block"><FieldConditionBuilder v-model="ruleForm.whitelist" :read-only="!canManageRules" :title="t('detect.editor.whitelist')" :add-label="t('detect.editor.addWhitelist')" :empty-hint="t('detect.noWhitelistHint')" :fields="fieldDefs" :reference-sets="referenceSets" :field-placeholder="t('detect.fieldPlaceholder')" :value-placeholder="t('detect.valuePlaceholder')" /></div></section>

        <section class="detect-form-section"><div class="detect-form-section-title"><span>04</span><div><h3>{{ t('detect.advancedFields') }}</h3><p>{{ t('detect.advancedFieldsHint') }}</p></div></div><div v-if="ADVANCED_TYPES.includes(ruleForm.type) || rawOnlyRuleType" class="detect-advanced-warning"><b>{{ t('detect.advancedType') }}</b><span>{{ t('detect.advancedTypeHint') }}</span></div><div class="detect-form-grid compact-grid"><el-form-item :label="t('detect.routingField')"><el-select v-model="ruleForm.routingField" disabled :placeholder="t('detect.fieldPlaceholder')"><el-option v-if="ruleForm.routingField && !fieldDefs.some(field => field.fieldName === ruleForm.routingField)" :label="ruleForm.routingField" :value="ruleForm.routingField" /><el-option v-for="field in fieldDefs" :key="field.fieldName" :label="field.fieldName" :value="field.fieldName" /></el-select><span class="form-hint">{{ t('detect.routingField') }} = {{ t('detect.groupBy') }}</span></el-form-item><el-form-item v-if="STATEFUL_TYPES.includes(ruleForm.type)" :label="t('detect.allowedLateness')"><el-input v-model="ruleForm.lateAllowedLateness" :placeholder="t('detect.allowedLatenessPlaceholder')" /></el-form-item><el-form-item v-if="STATEFUL_TYPES.includes(ruleForm.type)" :label="t('detect.lateHandling')"><el-select v-model="ruleForm.lateHandling"><el-option :label="t('detect.lateHandlingDrop')" value="DROP" /><el-option :label="t('detect.lateHandlingAccept')" value="ACCEPT" /></el-select></el-form-item><el-form-item v-if="ruleForm.type === 'baseline'" :label="t('detect.warmup')"><el-input v-model.number="ruleForm.warmup" type="number" min="1" /></el-form-item><el-form-item v-if="ruleForm.type === 'baseline'" :label="t('detect.baselineWindows')"><el-input v-model.number="ruleForm.baselineWindows" type="number" min="1" /></el-form-item><el-form-item v-if="ruleForm.type === 'baseline'" :label="t('detect.sigma')"><el-input v-model.number="ruleForm.sigma" type="number" min="0" max="100" /></el-form-item><el-form-item :label="t('detect.ruleVersion')"><el-input v-model="ruleForm.version" /></el-form-item><el-form-item :label="t('detect.owner')"><el-select v-model="ruleForm.owner" filterable default-first-option allow-create clearable :placeholder="t('detect.ownerPlaceholder')"><el-option v-for="owner in ownerOptions" :key="owner" :label="owner" :value="owner" /></el-select></el-form-item><el-form-item :label="t('detect.contentPack')"><el-input v-model="ruleForm.contentPack" /></el-form-item><el-form-item :label="t('detect.contentVersion')"><el-input v-model="ruleForm.contentVersion" /></el-form-item></div><details class="advanced-json"><summary>{{ t('detect.rawRuleJson') }}</summary><p>{{ t('detect.rawRuleJsonHint') }}</p><textarea v-model="advancedJson" :readonly="!canManageRules" rows="12" spellcheck="false" /><div v-if="advancedError" class="detect-feedback error">{{ advancedError }}</div><el-button v-if="canManageRules" size="small" @click="applyAdvancedJson">{{ t('detect.applyRawJson') }}</el-button></details></section>

        <section class="detect-form-section lifecycle-section"><div class="detect-form-section-title"><span>05</span><div><h3>{{ t('detect.testAndRelease') }}</h3><p>{{ t('detect.testAndReleaseHint') }}</p></div></div><div class="lifecycle-row"><div><span class="form-label">{{ t('detect.ruleStatus') }}</span><el-tag :type="statusTag(ruleForm.status)" size="small">{{ lifecycleStatusLabel(ruleForm.status) }}</el-tag><span class="form-hint inline-hint">{{ ruleEditingId ? t('detect.lifecycleReadOnly') : t('detect.newRuleTesting') }}</span></div><div class="lifecycle-toggle"><span>{{ t('detect.executionToggle') }}</span><el-switch v-model="ruleForm.enabled" :disabled="!ruleEditingId || ruleForm.status !== 'ACTIVE'" /></div></div></section>
      </el-form>
      <div class="detect-editor-footer"><el-button v-if="canManageRules" @click="showTest = true">{{ t('forms.test') }}</el-button><el-button @click="closeRuleEditor">{{ t('common.cancel') }}</el-button><el-button v-if="canManageRules" type="primary" :loading="saving" @click="saveRule">{{ t('common.save') }}</el-button></div>
    </section>

    <el-drawer v-model="showTest" :title="t('detect.testTitle')" size="min(1100px, 96vw)">
    <section class="detect-test-workspace">
      <div class="workspace-section-head"><div><h2>{{ t('detect.testTitle') }}</h2><p>{{ t('detect.testHint') }}</p></div><el-tag type="info" size="small">{{ t('detect.isolatedTest') }}</el-tag></div>
      <div class="detect-test-grid">
        <div class="detect-test-form">
          <label>{{ t('detect.testRule') }}<el-select v-model="testRuleId" clearable :placeholder="t('detect.allRules')"><el-option :label="t('detect.allRules')" value="" /><el-option v-for="rule in rules" :key="rule.id" :label="rule.name" :value="String(rule.id)" /></el-select></label>
          <label>{{ t('common.source') }}<el-input v-model="testInput.source" /></label><label>{{ t('common.host') }}<el-input v-model="testInput.host" /></label>
          <label>{{ t('common.severity') }}<el-select v-model="testInput.severity"><el-option v-for="severity in SEVERITIES" :key="severity" :label="t('severities.' + severity) || severity" :value="severity" /></el-select></label>
          <label class="full-width">{{ t('detect.testMessage') }}<el-input v-model="testInput.message" /></label><label class="full-width">{{ t('detect.testFields') }}<el-input v-model="testInput.fieldsText" type="textarea" :rows="4" spellcheck="false" /></label>
          <details class="full-width"><summary>{{ t('detect.sampleSequence') }}</summary><el-input v-model="sampleEventsText" type="textarea" :rows="5" placeholder='[{"timestamp":"2026-01-01T00:00:00Z","source":"auth","msg":"Failed password","fields":{}}]' /></details>
          <p v-if="showRuleEditor" class="full-width form-hint">{{ t('detect.testingDraft') }}</p>
          <div class="detect-test-actions"><el-button v-if="canManageRules" type="primary" :loading="testing" :disabled="!rules.length && !showRuleEditor" @click="runIsolatedTest">{{ t('detect.runTest') }}</el-button><span>{{ t('detect.isolatedTestHint') }}</span></div>
        </div>
        <div class="detect-test-result">
          <EmptyState v-if="!testResult && !testError" :title="t('detect.testWaiting')" :description="t('detect.testWaitingHint')" /><div v-if="testError" class="detect-feedback error" role="alert">{{ testError }}</div>
          <template v-if="testResult"><div class="test-summary"><span>{{ t('detect.testChecked', { count: testResult.checked }) }}</span><el-tag type="success" size="small">{{ t('detect.testMatchedCount', { count: testResult.matched }) }}</el-tag><el-tag v-if="testResult.candidates" type="warning" size="small">{{ t('detect.testCandidateCount', { count: testResult.candidates }) }}</el-tag></div>
            <div v-for="trace in testResult.traces" :key="trace.id" class="test-trace" :class="trace.state.toLowerCase()"><div class="test-trace-head"><div><b>{{ trace.name }}</b><span class="mono">{{ trace.id }}</span></div><el-tag size="small" :type="trace.state === 'NO_MATCH' ? 'info' : trace.state === 'CANDIDATE' ? 'warning' : 'success'">{{ testStateLabel(trace.state) }}</el-tag></div><p>{{ trace.reason }}</p><div v-if="trace.conditions.length" class="test-condition-list"><div class="test-condition-head"><span>{{ t('detect.testTraceCondition') }}</span><span>{{ t('detect.testObserved') }}</span></div><div v-for="(item, index) in trace.conditions" :key="index" class="test-condition" :class="{ matched: item.matched }"><span class="condition-mark">{{ item.matched ? '✓' : '×' }}</span><span class="test-condition-expression"><small v-if="item.scope">{{ conditionScopeLabel(item.scope) }}</small><span class="mono">{{ item.condition.field }} {{ item.condition.op }} {{ item.condition.value }}</span></span><span>{{ item.observed }}</span></div></div><div v-else class="test-no-condition">{{ t('detect.testNoConditions') }}</div></div>
          </template>
        </div>
      </div>
    </section>
    </el-drawer>
    <section v-if="!showRuleEditor" class="detect-list-section"><el-input v-model="ruleKeyword" :placeholder="t('forms.search')" clearable style="margin-bottom:12px" /><div class="workspace-section-head list-head"><div><h2>{{ t('detect.rules') }}</h2><p>{{ t('detect.lifecycleHint') }}</p></div><span class="toolbar-count">{{ t('common.total', { total: rules.length }) }}</span></div><el-card shadow="never" class="detect-table-card"><el-table :data="rules" size="small" row-key="id"><el-table-column prop="name" :label="t('common.name')" min-width="180" show-overflow-tooltip /><el-table-column prop="type" :label="t('common.type')" width="150"><template #default="{ row }"><span>{{ typeLabel(row.type) }}</span></template></el-table-column><el-table-column prop="severity" :label="t('common.severity')" width="110"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column><el-table-column :label="t('detect.matchingConditions')" min-width="260" show-overflow-tooltip><template #default="{ row }"><span v-if="row.match?.length" class="mono">{{ row.match.map((condition: RuleCondition) => `${condition.field} ${condition.op} ${condition.value}`).join(' AND ') }}</span><span v-else-if="row.steps?.length">{{ t('detect.stepCount', { count: row.steps.length }) }}</span><span v-else>—</span></template></el-table-column><el-table-column :label="t('detect.ruleStatus')" width="110"><template #default="{ row }"><el-tag :type="statusTag(ruleStatus(row))" size="small">{{ lifecycleStatusLabel(ruleStatus(row)) }}</el-tag></template></el-table-column><el-table-column :label="t('common.actions')" width="250" fixed="right"><template #default="{ row }"><el-button v-if="canManageRules" link type="primary" size="small" @click="openRuleEditor(row)">{{ t('common.edit') }}</el-button><el-button v-if="canManageRules && ['DRAFT', 'TESTING'].includes(ruleStatus(row))" link size="small" @click="testSingleRule(row)">{{ t('detect.testRule') }}</el-button><el-button v-if="canActivate && ruleStatus(row) === 'ACTIVE'" link size="small" @click="toggleRule(row)">{{ t('common.disable') }}</el-button><el-button v-if="canActivate && ['DISABLED', 'DRAFT', 'TESTING'].includes(ruleStatus(row))" link size="small" @click="toggleRule(row)">{{ t('common.enable') }}</el-button><el-button v-if="canManageRules && ruleStatus(row) !== 'ARCHIVED'" link size="small" @click="copyRuleAsDraft(row)">{{ t('common.copy') }}</el-button><el-button v-if="canManageRules && ['DRAFT', 'DISABLED'].includes(ruleStatus(row))" link type="danger" size="small" @click="removeRule(row)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table><EmptyState v-if="!loading && !rules.length" :title="t('detect.noRules')" :description="t('detect.noRulesHint')" /><div v-if="loading" class="detect-loading">{{ t('common.loading') }}</div></el-card></section>
  </div>
</template>

<style scoped>
.detect-view { display: flex; flex-direction: column; gap: 16px; }
.detect-stat-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.detect-stat { display: flex; flex-direction: column; gap: 8px; }.detect-stat span, .workspace-section-head p, .detect-form-section-title p, .form-hint { color: var(--ns-text-3); font-size: 12px; }.detect-stat b { color: var(--ns-text); font-size: 26px; line-height: 1; font-variant-numeric: tabular-nums; }.danger-text { color: var(--ns-danger) !important; }
.detect-feedback { display: flex; align-items: center; gap: 8px; padding: 9px 12px; border-radius: var(--ns-radius-md); font-size: 12px; }.detect-feedback span { overflow-wrap: anywhere; }.detect-feedback.error { color: var(--ns-danger); border: 1px solid color-mix(in srgb, var(--ns-danger) 28%, var(--ns-border)); background: color-mix(in srgb, var(--ns-danger) 7%, var(--ns-surface)); }
.detect-test-workspace, .detect-editor-workspace, .detect-list-section { min-width: 0; }.detect-test-workspace, .detect-editor-workspace { padding: 18px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-md); background: var(--ns-surface); }.workspace-section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }.workspace-section-head h2 { margin: 0; color: var(--ns-text); font-size: 17px; font-weight: 650; }.workspace-section-head p { margin: 5px 0 0; line-height: 1.5; }
.detect-test-grid { display: grid; grid-template-columns: minmax(360px, .9fr) minmax(0, 1.1fr); gap: 18px; }.detect-test-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; align-content: start; }.detect-test-form label, .lifecycle-row > div { display: flex; flex-direction: column; gap: 5px; color: var(--ns-text-2); font-size: 12px; }.detect-test-form label .el-input, .detect-test-form label .el-select { width: 100%; }.full-width { grid-column: 1 / -1; }.detect-test-actions { grid-column: 1 / -1; display: flex; align-items: center; gap: 10px; margin-top: 3px; }.detect-test-actions span { color: var(--ns-text-3); font-size: 11px; }.detect-test-result { min-width: 0; min-height: 270px; padding: 12px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-sm); background: var(--ns-bg-subtle); }.detect-test-result :deep(.empty-state) { padding: 42px 16px; }
.test-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; color: var(--ns-text-2); font-size: 12px; }.test-trace { margin-bottom: 8px; padding: 10px; border: 1px solid var(--ns-border); border-left: 3px solid var(--ns-info); border-radius: var(--ns-radius-sm); background: var(--ns-surface); }.test-trace.matched { border-left-color: var(--ns-success); }.test-trace.candidate { border-left-color: var(--ns-warning); }.test-trace-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.test-trace-head > div { min-width: 0; }.test-trace-head b, .test-trace-head .mono { display: block; }.test-trace-head b { color: var(--ns-text); }.test-trace-head .mono { margin-top: 2px; color: var(--ns-text-3); font-size: 10px; }.test-trace p { margin: 5px 0 8px; color: var(--ns-text-2); font-size: 11px; }.test-condition-list { display: grid; gap: 4px; }.test-condition-head { display: grid; grid-template-columns: minmax(0, 1fr) minmax(55px, .5fr); gap: 5px; color: var(--ns-text-3); font-size: 10px; font-weight: 600; }.test-condition { display: grid; grid-template-columns: 16px minmax(0, 1fr) minmax(55px, .5fr); gap: 5px; align-items: center; color: var(--ns-text-3); font-size: 10px; }.test-condition.matched { color: var(--ns-text-2); }.condition-mark { font-weight: 700; color: var(--ns-danger); }.test-condition.matched .condition-mark { color: var(--ns-success); }.test-condition-expression { min-width: 0; }.test-condition-expression small { display: block; margin-bottom: 2px; color: var(--ns-text-3); font-size: 9px; }.test-condition .mono { overflow-wrap: anywhere; }.test-no-condition { color: var(--ns-text-3); font-size: 11px; }
.detect-editor-workspace { padding-bottom: 0; }.workspace-section-actions { display: flex; align-items: center; gap: 8px; }.detect-editor-form { display: flex; flex-direction: column; gap: 12px; }.detect-form-section { padding: 16px 0; border-top: 1px solid var(--ns-border); }.detect-form-section:first-child { border-top: 0; padding-top: 0; }.detect-form-section-title { display: flex; gap: 10px; margin-bottom: 14px; }.detect-form-section-title > span { color: var(--ns-accent-fg); font-family: var(--ns-font-mono); font-size: 11px; font-weight: 700; }.detect-form-section-title h3 { margin: 0; color: var(--ns-text); font-size: 14px; font-weight: 650; }.detect-form-section-title p { margin: 4px 0 0; }.detect-form-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px 12px; }.compact-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }.detect-form-grid :deep(.el-form-item) { margin-bottom: 0; }.detect-form-grid :deep(.el-select), .detect-form-grid :deep(.el-input) { width: 100%; }.condition-block { margin-top: 10px; padding: 11px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-sm); background: var(--ns-bg-subtle); }.condition-block-head, .condition-group-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }.condition-block-head b { color: var(--ns-text-2); font-size: 12px; }.condition-group { margin-top: 8px; padding: 9px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-sm); background: var(--ns-surface); }.condition-group-head { margin-bottom: 6px; color: var(--ns-text-3); font-size: 11px; }.condition-row { display: grid; grid-template-columns: minmax(140px, .85fr) 130px minmax(140px, 1fr) auto; gap: 6px; align-items: center; margin-bottom: 6px; }.condition-row :deep(.el-input), .condition-row :deep(.el-select) { width: 100%; }.form-hint { margin: 8px 0 0; line-height: 1.5; }.inline-hint { margin-left: 8px; }.detect-advanced-warning { display: flex; gap: 8px; align-items: baseline; margin-bottom: 12px; padding: 9px 11px; border: 1px solid color-mix(in srgb, var(--ns-warning) 30%, var(--ns-border)); border-radius: var(--ns-radius-sm); background: color-mix(in srgb, var(--ns-warning) 8%, var(--ns-surface)); font-size: 12px; }.detect-advanced-warning b { color: var(--ns-warning); }.detect-advanced-warning span { color: var(--ns-text-2); }.advanced-json { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--ns-border); }.advanced-json summary { color: var(--ns-text-2); cursor: pointer; font-size: 12px; font-weight: 600; }.advanced-json p { color: var(--ns-text-3); font-size: 11px; }.advanced-json textarea { display: block; width: 100%; box-sizing: border-box; margin: 8px 0; padding: 10px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-sm); background: var(--ns-bg-inset); color: var(--ns-text); font: 11px/1.5 var(--ns-font-mono); resize: vertical; }.lifecycle-section { padding-bottom: 18px; }.lifecycle-row { display: flex; justify-content: space-between; gap: 16px; align-items: center; }.lifecycle-row > div { flex-direction: row; align-items: center; }.form-label { color: var(--ns-text-2); font-size: 12px; }.lifecycle-toggle { white-space: nowrap; }.detect-editor-footer { position: sticky; bottom: 0; z-index: 5; background: var(--ns-surface); display: flex; justify-content: flex-end; gap: 8px; padding: 14px 0 0; border-top: 1px solid var(--ns-border); }.detect-list-section { padding-top: 2px; }.list-head { align-items: center; margin-bottom: 10px; }.toolbar-count { color: var(--ns-text-3); font-size: 12px; }.detect-table-card .el-card__body { padding: 0; }.detect-loading { padding: 20px; color: var(--ns-text-3); text-align: center; font-size: 12px; }
.field-option { display: flex; flex-direction: column; gap: 2px; line-height: 1.25; }.field-option small { color: var(--ns-text-3); font-size: 10px; }
@media (max-width: 1000px) { .detect-stat-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.detect-test-grid { grid-template-columns: 1fr; }.detect-form-grid, .compact-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .detect-stat-grid, .detect-test-form, .detect-form-grid, .compact-grid { grid-template-columns: 1fr; }.full-width { grid-column: auto; }.condition-row { grid-template-columns: 1fr; }.lifecycle-row { align-items: flex-start; flex-direction: column; }.lifecycle-row > div { align-items: flex-start; flex-direction: column; }.workspace-section-head { flex-direction: column; }.workspace-section-actions { width: 100%; justify-content: space-between; } }
</style>
