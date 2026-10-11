<script setup lang="ts">
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { computed, inject, onMounted, onUnmounted, ref, watch } from 'vue'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/loading/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/switch/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { vLoading } from 'element-plus/es/components/loading/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import PageHeader from '../components/PageHeader.vue'
import PagerBar from '../components/PagerBar.vue'
import { useLatestRequest } from '../composables/useLatestRequest'
import EmptyState from '../components/EmptyState.vue'
import FieldConditionBuilder from '../components/FieldConditionBuilder.vue'
import RuleRevisionHistory from '../components/RuleRevisionHistory.vue'
import SevBadge from '../components/SevBadge.vue'
import { WORKBENCH_STATE } from '../app/workbenchState'
import {
  activateGasRule, deleteGasRule, gasStats, listRulePage, SEVERITIES, updateGasRule,
  listFields, listWatchlists, listTechniques,
  type FieldDef, type GasStats, type ReferenceSet, type RuleCondition, type RuleSpec, type Technique,
} from '../api'
import { useI18n } from '../composables/useI18n'
import { useConfirm } from '../composables/useConfirm'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { tOr } from '../utils/i18nLabel'
import { ApiError } from '../api/core'
import { ADVANCED_TYPES, RULE_TYPES, STATEFUL_TYPES, clone, normalizeRuleSpec, textValue } from '../lib/detection-editor'
import { useDetectRuleEditor } from '../composables/detect/useDetectRuleEditor'
import { useDetectRuleTest } from '../composables/detect/useDetectRuleTest'

const route = useRoute()
const router = useRouter()
const investigationReturn = computed(() => typeof route.query.returnTo === 'string' && /^\/(alarms|cases)(?:[?#]|$)/.test(route.query.returnTo) ? route.query.returnTo : '')
const ruleKeyword = ref('')
const ruleStatusFilter = ref('')
const { t } = useI18n()
const { confirmDanger } = useConfirm()
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('detect-rules')
const state = inject(WORKBENCH_STATE, null)
const currentRole = computed(() => state?.currentRole.value ?? '')
const canActivate = computed(() => ['admin', 'role_admin'].includes(currentRole.value.toLowerCase()))
const canManageRules = computed(() => ['admin', 'role_admin', 'analyst', 'role_analyst'].includes(currentRole.value.toLowerCase()))
const rules = ref<RuleSpec[]>([])
const rulesTotal = ref(0)
const listRequests = useLatestRequest()
let pendingPagination: LocationQueryRaw | null = null
function updatePagination(patch: LocationQueryRaw): void {
  // The pager emits size and page reset in one tick. Merge them before
  // navigating so the second event cannot restore the previous page size.
  pendingPagination = { ...(pendingPagination ?? route.query), ...patch }
  queueMicrotask(() => {
    if (!pendingPagination || disposed) { pendingPagination = null; return }
    const query = pendingPagination
    pendingPagination = null
    void router.push({ query })
  })
}
const rulePage = computed({
  get: () => { const value = Number(route.query.page); return Number.isSafeInteger(value) && value >= 1 && value <= 2147483647 ? value : 1 },
  set: value => updatePagination({ page: value > 1 ? String(value) : undefined }),
})
const rulePageSize = computed({
  get: () => [10, 20, 50].includes(Number(route.query.size)) ? Number(route.query.size) : 20,
  set: value => updatePagination({ size: String(value), page: undefined }),
})
const fieldDefs = ref<FieldDef[]>([])
const referenceSets = ref<ReferenceSet[]>([])
const techniques = ref<Technique[]>([])
const gasStat = ref<GasStats>({ rules: 0, eventCount: 0, alertCount: 0, dropCount: 0, suppressedCount: 0, queueLoad: 0 })
const loading = ref(false)
const supportLoading = ref(false)
const loadError = ref('')
const fieldLoadError = ref('')
const referenceLoadError = ref('')
const techniqueLoadError = ref('')
const actionMessage = ref('')
const listActionBusy = ref(false)
let disposed = false
onUnmounted(() => { disposed = true })
const historyOpen = ref(false)
const historyRuleId = ref('')
const editor = useDetectRuleEditor({
  canManageRules, canActivate, loadError, reloadRules: loadRules, toggleRule,
  applySample: sample => preview.applySample(sample),
})
const {
  editorLoading, saving, saveError, saveNotice, saveConflict, loadedRevisionToken,
  conditionErrors, ruleFieldErrors, scopeSection, logicSection, alertSection,
  showRuleEditor, ruleEditingId, sourceRule, advancedJson, advancedError, ruleForm,
  changes, rawOnlyRuleType, publicationLink, onGroupByChange, acceptRestoredRule,
  syncEditorRoute, closeRuleEditor, saveRule, applyAdvancedJson,
  downloadRuleDraft, publishSavedRule, addMatchAnyGroup, addStep,
} = editor
const preview = useDetectRuleTest({
  canManageRules, rules, showRuleEditor, ruleEditingId, ruleForm, loadedRevisionToken,
  identity: () => route.fullPath, draftSpec: editor.draftSpec,
})
const {
  showTest, testInput, testRuleId, testError, testing, sampleEventsText, testResult,
  testReceipt, testReceiptStale, testStateLabel, conditionScopeLabel, runIsolatedTest,
} = preview
const ownerOptions = computed(() => {
  const values = new Set(state?.operatorOptions?.value ?? [])
  const current = ruleForm.value.owner.trim()
  if (current) values.add(current)
  return [...values].filter(Boolean)
})
async function loadSupport(): Promise<void> {
  if (supportLoading.value || disposed) return
  supportLoading.value = true
  const [statResult, fieldResult, refsetResult, techniqueResult] = await Promise.allSettled([gasStats(), listFields(), listWatchlists(), listTechniques()])
  if (disposed) return
  if (statResult.status === 'fulfilled') gasStat.value = statResult.value
  if (fieldResult.status === 'fulfilled') {
    fieldDefs.value = fieldResult.value
    fieldLoadError.value = ''
  } else {
    fieldLoadError.value = fieldResult.reason instanceof Error ? fieldResult.reason.message : String(fieldResult.reason)
  }
  if (refsetResult.status === 'fulfilled') {
    referenceSets.value = refsetResult.value.map(list => ({ id: list.name, name: list.name, description: '', entries: [], size: list.size }))
    referenceLoadError.value = ''
  } else {
    referenceLoadError.value = refsetResult.reason instanceof Error ? refsetResult.reason.message : String(refsetResult.reason)
  }
  if (techniqueResult.status === 'fulfilled') {
    techniques.value = techniqueResult.value.items
    techniqueLoadError.value = ''
  } else {
    techniqueLoadError.value = techniqueResult.reason instanceof Error ? techniqueResult.reason.message : String(techniqueResult.reason)
  }
  supportLoading.value = false
}

function openRuleEditor(row?: unknown): void {
  if (!canManageRules.value) return
  const rule = row ? normalizeRuleSpec(row) : null
  void router.push(rule ? { name: 'rule-edit', params: { ruleId: String(rule.id) }, query: route.query } : { name: 'rule-new', query: route.query })
}
function openRuleHistory(row?: unknown): void {
  if (!canManageRules.value || saving.value) return
  const id = row ? normalizeRuleSpec(row)?.id : ruleEditingId.value
  if (!id) return
  historyRuleId.value = String(id)
  historyOpen.value = true
}
function ruleMutationMessage(error: unknown): string {
  return error instanceof ApiError && [412, 428].includes(error.status)
    ? t('detect.listConcurrentEdit') : error instanceof Error ? error.message : String(error)
}

function ruleUpdateSpec(rule: RuleSpec, enabled: boolean, status: string): Partial<RuleSpec> {
  const spec = clone(rule) as Partial<RuleSpec>; spec.id = String(rule.id); spec.enabled = enabled; spec.status = status; return spec
}

async function toggleRule(row: unknown): Promise<boolean> {
  if (!canManageRules.value || listActionBusy.value || disposed) return false
  const normalized = normalizeRuleSpec(row)
  if (!normalized) return false
  const rule = clone(normalized)
  listActionBusy.value = true
  actionMessage.value = ''
  try {
    const status = textValue(rule.status).toUpperCase()
    if (status === 'ACTIVE' || rule.enabled) {
      if (!await confirmDanger(t('detect.disableRuleConfirm', { name: textValue(rule.name) || String(rule.id) }))) return false
      if (!canManageRules.value || disposed) return false
      await updateGasRule(String(rule.id), ruleUpdateSpec(rule, false, 'DISABLED'), rule.revisionToken)
      await loadRules()
      return !disposed
    }
    if (!canActivate.value) { actionMessage.value = t('detect.activationAdminOnly'); return false }
    if (status === 'ARCHIVED') return false
    await activateGasRule(String(rule.id), rule.revisionToken)
    await loadRules()
    return !disposed
  } catch (error) {
    if (!disposed) actionMessage.value = ruleMutationMessage(error)
    return false
  } finally {
    listActionBusy.value = false
  }
}

async function removeRule(row: unknown): Promise<void> {
  if (!canManageRules.value || listActionBusy.value || disposed) return
  const rule = normalizeRuleSpec(row)
  if (!rule) return
  if (ruleStatus(rule) === 'ACTIVE') {
    actionMessage.value = t('detect.disableBeforeDelete')
    return
  }
  const { id, revisionToken } = rule
  listActionBusy.value = true
  try {
    if (!await confirmDanger(t('detect.deleteRuleConfirm'))) return
    if (!canManageRules.value || disposed) return
    await deleteGasRule(String(id), revisionToken)
    await loadRules()
  } catch (error) {
    if (!disposed) actionMessage.value = ruleMutationMessage(error)
  } finally { listActionBusy.value = false }
}

function copyRuleAsDraft(row: unknown): void {
  if (!canManageRules.value) return
  const rule = normalizeRuleSpec(row)
  if (rule) void router.push({ name: 'rule-new', query: { ...route.query, copy: String(rule.id) } })
}

function testSingleRule(row: unknown): void {
  if (!canManageRules.value) return
  const rule = normalizeRuleSpec(row)
  if (!rule) return
  testRuleId.value = String(rule.id)
  showTest.value = true
}

function ruleStatus(row: unknown): string {
  const rule = row as Partial<RuleSpec>
  return textValue(rule.status).toUpperCase() || (rule.enabled ? 'ACTIVE' : 'DRAFT')
}
function statusTag(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'ACTIVE') return 'success'; if (status === 'TESTING') return 'warning'; if (status === 'DISABLED') return 'info'; if (status === 'ARCHIVED') return 'danger'; return 'primary'
}
function lifecycleStatusLabel(status: string): string {
  const value = String(status ?? '')
  return value ? tOr(t, 'detect.status.' + value, value) : ''
}
function typeLabel(type: string): string {
  const value = String(type ?? '')
  if (!value) return ''
  const translated = tOr(t, 'detect.ruleTypes.' + value, '')
  if (translated) return translated
  return ADVANCED_TYPES.includes(value) ? `${value} · ${t('detect.advancedType')}` : value
}
function applyRuleFilters(): void {
  void router.push({ query: { ...route.query, q: ruleKeyword.value.trim() || undefined,
    status: ruleStatusFilter.value || undefined, page: undefined } })
}

async function loadRuleList(): Promise<void> {
  if (route.name !== 'detect' || disposed) return
  const request = listRequests.start()
  loadError.value = ''
  loading.value = true
  preview.resetPreview()
  try {
    const result = await listRulePage({ page: rulePage.value, size: rulePageSize.value,
      q: typeof route.query.q === 'string' ? route.query.q : undefined,
      status: typeof route.query.status === 'string' ? route.query.status : undefined,
      technique: typeof route.query.technique === 'string' ? route.query.technique : undefined,
      ...(typeof route.query.reference === 'string' ? { reference: route.query.reference } : {}) }, { signal: request.signal })
    if (!request.isCurrent()) return
    rules.value = result.items.map(normalizeRuleSpec).filter((rule): rule is RuleSpec => rule !== null)
    rulesTotal.value = result.total
    const lastPage = Math.max(1, result.totalPages ?? Math.ceil(result.total / result.size))
    if (rulePage.value > lastPage) await router.replace({ query: { ...route.query, page: lastPage > 1 ? String(lastPage) : undefined } })
  } catch (failure) {
    if (request.isCurrent()) { rules.value = []; rulesTotal.value = 0; loadError.value = String(failure) }
  } finally {
    if (request.isCurrent()) loading.value = false
  }
}

async function loadRules(): Promise<void> { await Promise.all([loadSupport(), loadRuleList()]) }
async function refreshRules(): Promise<void> {
  if (saving.value) return
  if (route.meta.editor && !await changes.canLeave()) return
  await Promise.all([loadRules(), route.meta.editor ? syncEditorRoute() : Promise.resolve()])
}
watch(() => [route.name, route.params.ruleId, route.query.copy], () => { void syncEditorRoute() }, { immediate: true })
watch(() => [route.name, route.query.q, route.query.status, route.query.reference, route.query.technique, route.query.page, route.query.size], () => {
  if (route.name !== 'detect') { listRequests.cancel(); return }
  ruleKeyword.value = typeof route.query.q === 'string' ? route.query.q : ''
  ruleStatusFilter.value = typeof route.query.status === 'string' ? route.query.status : ''
  void loadRuleList()
}, { immediate: true })
onMounted(loadSupport)
watch(() => route.fullPath, () => { historyOpen.value = false })
watch(() => route.query.ruleId, id => { if (route.name === 'detect' && typeof id === 'string' && id) void router.replace({ name: 'rule-edit', params: { ruleId: id }, query: { ...route.query, ruleId: undefined } }) }, { immediate: true })
</script>

<template>
  <div class="page-pad view-enter detect-view">
    <p v-if="editorLoading" role="status">{{ t('common.loading') }}</p>
    <el-button v-if="investigationReturn" @click="router.push(investigationReturn)">{{ t('analystJourney.returnToInvestigation') }}</el-button>
    <PageHeader :eyebrow="t('menuGroup.detectAndResponse')" :title="t('detect.title')" :description="t('detect.workspaceDescription')">
      <template #actions><el-button v-if="route.meta.editor" :disabled="saving" @click="closeRuleEditor">{{ t('forms.back') }}</el-button><el-select v-if="!route.meta.editor" v-model="ruleStatusFilter" size="small" @change="applyRuleFilters" clearable :placeholder="t('common.filter')" style="width:150px"><el-option v-for="status in ['DRAFT', 'TESTING', 'ACTIVE', 'DISABLED', 'ARCHIVED']" :key="status" :label="lifecycleStatusLabel(status)" :value="status" /></el-select><el-button size="small" :loading="loading || supportLoading || editorLoading" @click="refreshRules">{{ t('common.refresh') }}</el-button><el-button v-if="canManageRules && !route.meta.editor" type="primary" size="small" @click="openRuleEditor()">{{ t('detect.createRule') }}</el-button></template>
    </PageHeader>
    <div v-if="showRuleEditor && !canManageRules" class="page-readonly-hint">{{ t('detect.readOnly') }}</div>

    <div v-if="referenceLoadError" class="detect-feedback error" role="alert"><strong>{{ t('ueba.watchlists') }}</strong><span>{{ referenceLoadError }}</span><el-button size="small" :loading="loading || supportLoading || editorLoading" @click="refreshRules">{{ t('common.refresh') }}</el-button></div>
    <div v-if="loadError" class="detect-feedback error" role="alert"><strong>{{ t('detect.loadFailed') }}</strong><span>{{ loadError }}</span><el-button size="small" @click="refreshRules">{{ t('common.refresh') }}</el-button></div>
    <div v-if="actionMessage" class="detect-feedback error" role="alert">{{ actionMessage }}</div>

    <div v-if="!route.meta.editor" class="detect-stat-grid">
      <el-card shadow="never"><div class="detect-stat"><span>{{ t('detect.rulesCount') }}</span><b>{{ gasStat.rules ?? 0 }}</b></div></el-card>
      <el-card shadow="never"><div class="detect-stat"><span>{{ t('detect.eventsCount') }}</span><b>{{ gasStat.eventCount ?? 0 }}</b></div></el-card>
      <el-card shadow="never"><div class="detect-stat"><span>{{ t('detect.alarmsCount') }}</span><b class="danger-text">{{ gasStat.alertCount ?? 0 }}</b></div></el-card>
      <el-card shadow="never"><div class="detect-stat"><span>{{ t('detect.queueLoad') }}</span><b>{{ (gasStat.queueLoad * 100).toFixed(0) }}%</b></div></el-card>
    </div>


    <section v-if="showRuleEditor" class="detect-editor-workspace" :class="{ 'detect-editor-readonly': !canManageRules }">
      <div class="workspace-section-head"><div><div class="page-eyebrow">{{ t('detect.editorEyebrow') }}</div><h2>{{ ruleEditingId ? t('detect.editor.editRule') : t('detect.createRule') }}</h2><p>{{ t('detect.editorHint') }}</p></div><div class="workspace-section-actions"><el-button v-if="canManageRules && ruleEditingId" size="small" :disabled="saving" @click="openRuleHistory()">{{ t('detect.revisionHistory') }}</el-button><el-tag v-if="ruleEditingId" :type="statusTag(ruleForm.status)" size="small">{{ lifecycleStatusLabel(ruleForm.status) }}</el-tag><el-button size="small" :disabled="saving" @click="closeRuleEditor">{{ t('common.cancel') }}</el-button></div></div>
      <section class="workspace-hint" :aria-label="t('analystJourney.publicationVerification')">
        <p>1. Save → 2. Test the current saved revision → 3. Publisher enables → 4. Verify genuine matching alarms. Isolated tests never ingest events or create alarms.</p>
        <p v-if="testReceipt">{{ t('analystJourney.testReceipt', { rule: testReceipt.ruleId, revision: testReceipt.revision || t('analystJourney.unsavedDraft'), at: testReceipt.at, matched: testReceipt.result.matched }) }} <strong v-if="testReceiptStale">{{ t('analystJourney.changedSinceTest') }}</strong></p>
        <el-button v-if="canActivate && sourceRule && !sourceRule.enabled" :disabled="changes.dirty.value || saving" @click="publishSavedRule">{{ t('analystJourney.enableSavedRevision') }}</el-button>
        <p v-else-if="!canActivate && publicationLink">{{ t('analystJourney.publicationAdmin') }} <a :href="publicationLink">{{ publicationLink }}</a></p>
        <el-button v-if="ruleEditingId" @click="router.push({ name: 'alarms', query: { rule: ruleEditingId } })">{{ t('analystJourney.verifyAlarms') }}</el-button>
        <el-button @click="router.push({ name: 'search' })">{{ t('analystJourney.findSamples') }}</el-button>
        <p>{{ t('analystJourney.ruleEngineCounters', { events: gasStat.eventCount, alerts: gasStat.alertCount }) }} </p>
      </section>
      <p v-if="saving" class="form-hint" role="status">{{ t('common.busySaving') }}</p>
      <el-form label-position="top" class="detect-editor-form" :disabled="!canManageRules || saving">
        <section ref="scopeSection" class="detect-form-section"><div class="detect-form-section-title"><span>01</span><div><h3>{{ t('detect.dataScope') }}</h3><p>{{ t('detect.dataScopeHint') }}</p></div></div><div class="detect-form-grid"><el-form-item :label="t('common.name')" required :error="ruleFieldErrors.name"><el-input v-model="ruleForm.name" :placeholder="t('detect.editor.namePlaceholder')" /></el-form-item><el-form-item :label="t('common.type')"><el-select v-model="ruleForm.type"><el-option v-if="rawOnlyRuleType" :label="typeLabel(ruleForm.type)" :value="ruleForm.type" /><el-option v-for="type in RULE_TYPES" :key="type" :label="typeLabel(type)" :value="type" /></el-select></el-form-item><el-form-item :label="t('common.severity')"><el-select v-model="ruleForm.severity"><el-option v-for="severity in SEVERITIES" :key="severity" :label="tOr(t, 'severities.' + severity, severity)" :value="severity" /></el-select></el-form-item><el-form-item :label="t('detect.editor.window')"><el-input v-model="ruleForm.window" :placeholder="t('detect.editor.windowPlaceholder')" /></el-form-item></div></section>

        <section ref="logicSection" class="detect-form-section">
          <div class="detect-form-section-title"><span>02</span><div><h3>{{ t('detect.detectionLogic') }}</h3><p>{{ t('detect.detectionLogicHint') }}</p></div></div>
          <template v-if="ruleForm.type === 'correlation'">
            <div class="condition-block">
              <div class="condition-block-head"><b>{{ t('detect.correlationSteps') }}</b><el-button v-if="canManageRules" size="small" plain @click="addStep">{{ t('detect.addStep') }}</el-button></div>
              <div v-for="(step, stepIndex) in ruleForm.steps" :key="stepIndex" class="condition-group">
                <div class="condition-group-head"><span>{{ t('detect.step') }} {{ stepIndex + 1 }}</span><el-button v-if="canManageRules && ruleForm.steps.length > 1" link type="danger" size="small" @click="ruleForm.steps.splice(stepIndex, 1)">{{ t('common.delete') }}</el-button></div>
                <FieldConditionBuilder v-model="ruleForm.steps[stepIndex]" :read-only="!canManageRules || saving" :errors="conditionErrors.steps[stepIndex]" :fields="fieldDefs" :reference-sets="referenceSets" :add-label="t('detect.addCondition')" :empty-hint="t('detect.noConditions')" :field-placeholder="t('detect.fieldPlaceholder')" :value-placeholder="t('detect.valuePlaceholder')" />
              </div>
              <EmptyState v-if="!ruleForm.steps.length" :title="t('detect.noSteps')" :description="t('detect.addStepHint')" />
            </div>
          </template>
          <template v-else>
            <div class="condition-block">
              <FieldConditionBuilder v-model="ruleForm.match" :read-only="!canManageRules || saving" :errors="conditionErrors.match" :title="t('detect.allConditions')" :add-label="t('detect.addCondition')" :empty-hint="t('detect.noConditions')" :fields="fieldDefs" :reference-sets="referenceSets" :field-placeholder="t('detect.fieldPlaceholder')" :value-placeholder="t('detect.valuePlaceholder')" />
            </div>
            <div class="condition-block">
              <div class="condition-block-head"><b>{{ t('detect.anyConditionGroup') }}</b><el-button v-if="canManageRules" size="small" plain @click="addMatchAnyGroup">{{ t('detect.addGroup') }}</el-button></div>
              <div v-for="(group, groupIndex) in ruleForm.matchAny" :key="groupIndex" class="condition-group">
                <div class="condition-group-head"><span>{{ t('detect.conditionGroup') }} {{ groupIndex + 1 }}</span><el-button v-if="canManageRules" link type="danger" size="small" @click="ruleForm.matchAny.splice(groupIndex, 1)">{{ t('common.delete') }}</el-button></div>
                <FieldConditionBuilder v-model="ruleForm.matchAny[groupIndex]" :read-only="!canManageRules || saving" :errors="conditionErrors.matchAny[groupIndex]" :fields="fieldDefs" :reference-sets="referenceSets" :add-label="t('detect.addCondition')" :empty-hint="t('detect.noConditions')" :field-placeholder="t('detect.fieldPlaceholder')" :value-placeholder="t('detect.valuePlaceholder')" />
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

        <section ref="alertSection" class="detect-form-section"><div class="detect-form-section-title"><span>03</span><div><h3>{{ t('detect.alertContent') }}</h3><p>{{ t('detect.alertContentHint') }}</p></div></div><div class="detect-form-grid"><el-form-item :label="t('detect.editor.alertTitle')"><el-input v-model="ruleForm.alertTitle" :placeholder="t('detect.editor.alertTitlePlaceholder')" /></el-form-item><el-form-item :label="t('detect.editor.alertDescription')"><el-input v-model="ruleForm.alertDescription" :placeholder="t('detect.editor.alertDescriptionPlaceholder')" /></el-form-item><el-form-item :label="t('detect.compatMessage')"><el-input v-model="ruleForm.message" /></el-form-item><el-form-item :label="t('detect.mitre')"><el-select v-model="ruleForm.mitre" filterable default-first-option clearable placeholder="T1110"><el-option v-if="ruleForm.mitre && !techniques.some(item => item.id === ruleForm.mitre)" :label="ruleForm.mitre + ' (custom)'" :value="ruleForm.mitre" /><el-option v-for="technique in techniques" :key="technique.id" :label="`${technique.id} · ${technique.name}`" :value="technique.id" /></el-select><span v-if="techniqueLoadError" class="form-hint">{{ t('detect.fieldCatalogFallback') }}</span></el-form-item></div><div class="condition-block"><FieldConditionBuilder v-model="ruleForm.whitelist" :read-only="!canManageRules || saving" :errors="conditionErrors.whitelist" :title="t('detect.editor.whitelist')" :add-label="t('detect.editor.addWhitelist')" :empty-hint="t('detect.noWhitelistHint')" :fields="fieldDefs" :reference-sets="referenceSets" :field-placeholder="t('detect.fieldPlaceholder')" :value-placeholder="t('detect.valuePlaceholder')" /></div></section>

        <section class="detect-form-section"><div class="detect-form-section-title"><span>04</span><div><h3>{{ t('detect.advancedFields') }}</h3><p>{{ t('detect.advancedFieldsHint') }}</p></div></div><div v-if="ADVANCED_TYPES.includes(ruleForm.type) || rawOnlyRuleType" class="detect-advanced-warning"><b>{{ t('detect.advancedType') }}</b><span>{{ t('detect.advancedTypeHint') }}</span></div><div class="detect-form-grid compact-grid"><el-form-item :label="t('detect.routingField')"><el-select v-model="ruleForm.routingField" disabled :placeholder="t('detect.fieldPlaceholder')"><el-option v-if="ruleForm.routingField && !fieldDefs.some(field => field.fieldName === ruleForm.routingField)" :label="ruleForm.routingField" :value="ruleForm.routingField" /><el-option v-for="field in fieldDefs" :key="field.fieldName" :label="field.fieldName" :value="field.fieldName" /></el-select><span class="form-hint">{{ t('detect.routingField') }} = {{ t('detect.groupBy') }}</span></el-form-item><el-form-item v-if="STATEFUL_TYPES.includes(ruleForm.type)" :label="t('detect.allowedLateness')"><el-input v-model="ruleForm.lateAllowedLateness" :placeholder="t('detect.allowedLatenessPlaceholder')" /></el-form-item><el-form-item v-if="STATEFUL_TYPES.includes(ruleForm.type)" :label="t('detect.lateHandling')"><el-select v-model="ruleForm.lateHandling"><el-option :label="t('detect.lateHandlingDrop')" value="DROP" /><el-option :label="t('detect.lateHandlingAccept')" value="ACCEPT" /></el-select></el-form-item><el-form-item v-if="ruleForm.type === 'baseline'" :label="t('detect.warmup')"><el-input v-model.number="ruleForm.warmup" type="number" min="1" /></el-form-item><el-form-item v-if="ruleForm.type === 'baseline'" :label="t('detect.baselineWindows')"><el-input v-model.number="ruleForm.baselineWindows" type="number" min="1" /></el-form-item><el-form-item v-if="ruleForm.type === 'baseline'" :label="t('detect.sigma')"><el-input v-model.number="ruleForm.sigma" type="number" min="0" max="100" /></el-form-item><el-form-item :label="t('detect.ruleVersion')"><el-input v-model="ruleForm.version" /></el-form-item><el-form-item :label="t('detect.owner')"><el-select v-model="ruleForm.owner" filterable default-first-option allow-create clearable :placeholder="t('detect.ownerPlaceholder')"><el-option v-for="owner in ownerOptions" :key="owner" :label="owner" :value="owner" /></el-select></el-form-item><el-form-item :label="t('detect.contentPack')"><el-input v-model="ruleForm.contentPack" /></el-form-item><el-form-item :label="t('detect.contentVersion')"><el-input v-model="ruleForm.contentVersion" /></el-form-item></div><details class="advanced-json"><summary>{{ t('detect.rawRuleJson') }}</summary><p>{{ t('detect.rawRuleJsonHint') }}</p><textarea v-model="advancedJson" :readonly="!canManageRules || saving" rows="12" spellcheck="false" /><div v-if="advancedError" class="detect-feedback error">{{ advancedError }}</div><el-button v-if="canManageRules" size="small" @click="applyAdvancedJson">{{ t('detect.applyRawJson') }}</el-button></details></section>

        <section class="detect-form-section lifecycle-section"><div class="detect-form-section-title"><span>05</span><div><h3>{{ t('detect.testAndRelease') }}</h3><p>{{ t('detect.testAndReleaseHint') }}</p></div></div><div class="lifecycle-row"><div><span class="form-label">{{ t('detect.ruleStatus') }}</span><el-tag :type="statusTag(ruleForm.status)" size="small">{{ lifecycleStatusLabel(ruleForm.status) }}</el-tag><span class="form-hint inline-hint">{{ ruleEditingId ? t('detect.lifecycleReadOnly') : t('detect.newRuleTesting') }}</span></div><div class="lifecycle-toggle"><span>{{ t('detect.executionToggle') }}</span><el-switch v-model="ruleForm.enabled" :disabled="saving || !ruleEditingId || ruleForm.status !== 'ACTIVE'" /></div></div></section>
      </el-form>
      <div v-if="saveError" class="detect-feedback error detect-save-feedback" role="alert">{{ saveError }}</div>
      <div v-if="saveConflict" class="detect-conflict-actions"><el-button @click="downloadRuleDraft">{{ t('detect.downloadDraft') }}</el-button><el-button @click="refreshRules">{{ t('detect.reloadCurrent') }}</el-button></div>
      <div v-if="!saveError && saveNotice" class="detect-feedback notice" role="status">{{ saveNotice }}</div>
      <div class="detect-editor-footer"><el-button v-if="canManageRules" @click="showTest = true">{{ t('forms.test') }}</el-button><el-button :disabled="saving" @click="closeRuleEditor">{{ t('common.cancel') }}</el-button><el-button v-if="canManageRules" type="primary" :loading="saving" @click="saveRule">{{ t('common.save') }}</el-button></div>
    </section>

    <RuleRevisionHistory v-if="historyOpen" v-model="historyOpen" :rule-id="historyRuleId" :can-restore="canActivate" :has-draft="changes.dirty.value" @busy="saving = $event" @restored="acceptRestoredRule" />

    <el-drawer v-model="showTest" :title="t('detect.testTitle')" size="min(1100px, 96vw)">
    <section class="detect-test-workspace">
      <div class="workspace-section-head"><div><h2>{{ t('detect.testTitle') }}</h2><p>{{ t('detect.testHint') }}</p></div><el-tag type="info" size="small">{{ t('detect.isolatedTest') }}</el-tag></div>
      <div class="detect-test-grid">
        <div class="detect-test-form">
          <label v-if="!showRuleEditor">{{ t('detect.testRule') }}<el-select v-model="testRuleId" clearable :placeholder="t('detect.pageRules')"><el-option :label="t('detect.pageRules')" value="" /><el-option v-for="rule in rules" :key="rule.id" :label="rule.name" :value="String(rule.id)" /></el-select></label>
          <p v-if="showRuleEditor">{{ t('analystJourney.lockedTestTarget', { name: ruleForm.name || ruleEditingId || t('analystJourney.newDraft'), revision: loadedRevisionToken || t('analystJourney.unsaved') }) }} </p>
          <label>{{ t('common.source') }}<el-input v-model="testInput.source" /></label><label>{{ t('common.host') }}<el-input v-model="testInput.host" /></label>
          <label>{{ t('common.severity') }}<el-select v-model="testInput.severity"><el-option v-for="severity in SEVERITIES" :key="severity" :label="tOr(t, 'severities.' + severity, severity)" :value="severity" /></el-select></label>
          <label class="full-width">{{ t('detect.testMessage') }}<el-input v-model="testInput.message" /></label><label class="full-width">{{ t('detect.testFields') }}<el-input v-model="testInput.fieldsText" type="textarea" :rows="4" spellcheck="false" /></label>
          <details class="full-width" :open="!!sampleEventsText"><summary>{{ t('detect.sampleSequence') }}</summary><el-input v-model="sampleEventsText" type="textarea" :rows="5" placeholder='[{"timestamp":"2026-01-01T00:00:00Z","source":"auth","msg":"Failed password","fields":{}}]' /></details>
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
    <section v-if="!route.meta.editor" class="detect-list-section"><p v-if="typeof route.query.reference === 'string'" class="form-hint">{{ t('detect.watchlistFilter', { name: route.query.reference }) }} <el-button link @click="router.push({ query: { ...route.query, reference: undefined, page: undefined } })">{{ t('detect.clearWatchlistFilter') }}</el-button></p><form class="rule-search" @submit.prevent="applyRuleFilters"><el-input v-model="ruleKeyword" :placeholder="t('forms.search')" :aria-label="t('forms.search')" clearable maxlength="256" @clear="applyRuleFilters" /><el-button native-type="submit" size="small" :loading="loading">{{ t('forms.search') }}</el-button></form><div class="workspace-section-head list-head"><div><h2>{{ t('detect.rules') }}</h2><p>{{ t('detect.lifecycleHint') }}</p></div><span class="toolbar-count">{{ t('common.total', { total: rulesTotal }) }}</span></div><el-card shadow="never" class="detect-table-card"><el-table v-loading="loading" :data="rules" size="small" row-key="id" border allow-drag-last-column @header-dragend="onHeaderDragEnd"><el-table-column prop="name" column-key="name" :label="t('common.name')" :width="columnWidth('name')" min-width="180" show-overflow-tooltip /><el-table-column prop="type" column-key="type" :label="t('common.type')" :width="columnWidth('type', 150)"><template #default="{ row }"><span>{{ typeLabel(row.type) }}</span></template></el-table-column><el-table-column prop="severity" column-key="severity" :label="t('common.severity')" :width="columnWidth('severity', 110)"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column><el-table-column column-key="match" :label="t('detect.matchingConditions')" :width="columnWidth('match')" min-width="260" show-overflow-tooltip><template #default="{ row }"><span v-if="row.match?.length" class="mono">{{ row.match.map((condition: RuleCondition) => `${condition.field} ${condition.op} ${condition.value}`).join(' AND ') }}</span><span v-else-if="row.steps?.length">{{ t('detect.stepCount', { count: row.steps.length }) }}</span><span v-else>—</span></template></el-table-column><el-table-column column-key="status" :label="t('detect.ruleStatus')" :width="columnWidth('status', 110)"><template #default="{ row }"><el-tag :type="statusTag(ruleStatus(row))" size="small">{{ lifecycleStatusLabel(ruleStatus(row)) }}</el-tag></template></el-table-column><el-table-column :label="t('common.actions')" width="340" fixed="right" :resizable="false"><template #default="{ row }"><el-button v-if="canManageRules" link type="primary" size="small" @click="openRuleEditor(row)">{{ t('common.edit') }}</el-button><el-button v-if="canManageRules" link size="small" @click="openRuleHistory(row)">{{ t('detect.revisionHistory') }}</el-button><el-button v-if="canManageRules && ['DRAFT', 'TESTING'].includes(ruleStatus(row))" link size="small" @click="testSingleRule(row)">{{ t('detect.testRule') }}</el-button><el-button v-if="canActivate && ruleStatus(row) === 'ACTIVE'" link size="small" :disabled="listActionBusy" @click="toggleRule(row)">{{ t('common.disable') }}</el-button><el-button v-if="canActivate && ['DISABLED', 'DRAFT', 'TESTING'].includes(ruleStatus(row))" link size="small" :disabled="listActionBusy" @click="toggleRule(row)">{{ t('common.enable') }}</el-button><el-button v-if="canManageRules && ruleStatus(row) !== 'ARCHIVED'" link size="small" @click="copyRuleAsDraft(row)">{{ t('common.copy') }}</el-button><el-button v-if="canManageRules && ['DRAFT', 'DISABLED'].includes(ruleStatus(row))" link type="danger" size="small" :disabled="listActionBusy" @click="removeRule(row)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table><PagerBar v-model:current-page="rulePage" v-model:page-size="rulePageSize" :total="rulesTotal" /><EmptyState v-if="!loading && !loadError && !rules.length" :title="t('detect.noRules')" :description="t('detect.noRulesHint')" /></el-card></section>
  </div>
</template>

<style scoped>
.rule-search { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.rule-search .el-input { min-width: 0; flex: 1; }
.detect-view { display: flex; flex-direction: column; gap: 16px; }
.detect-stat-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.detect-stat { display: flex; flex-direction: column; gap: 8px; }.detect-stat span, .workspace-section-head p, .detect-form-section-title p, .form-hint { color: var(--ns-text-3); font-size: 12px; }.detect-stat b { color: var(--ns-text); font-size: 26px; line-height: 1; font-variant-numeric: tabular-nums; }.danger-text { color: var(--ns-danger) !important; }
.detect-feedback { display: flex; align-items: center; gap: 8px; padding: 9px 12px; border-radius: var(--ns-radius-md); font-size: 12px; }.detect-feedback span { overflow-wrap: anywhere; }.detect-feedback.error { color: var(--ns-danger); border: 1px solid color-mix(in srgb, var(--ns-danger) 28%, var(--ns-border)); background: color-mix(in srgb, var(--ns-danger) 7%, var(--ns-surface)); }.detect-feedback.notice { color: var(--ns-warning); border: 1px solid color-mix(in srgb, var(--ns-warning) 28%, var(--ns-border)); background: color-mix(in srgb, var(--ns-warning) 7%, var(--ns-surface)); }
.detect-save-feedback, .detect-feedback.notice { margin: 14px 0 0; overflow-wrap: anywhere; }
.detect-test-workspace, .detect-editor-workspace, .detect-list-section { min-width: 0; }.detect-test-workspace, .detect-editor-workspace { padding: 18px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-md); background: var(--ns-surface); }.workspace-section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }.workspace-section-head h2 { margin: 0; color: var(--ns-text); font-size: 17px; font-weight: 650; }.workspace-section-head p { margin: 5px 0 0; line-height: 1.5; }
.detect-test-grid { display: grid; grid-template-columns: minmax(360px, .9fr) minmax(0, 1.1fr); gap: 18px; }.detect-test-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; align-content: start; }.detect-test-form label, .lifecycle-row > div { display: flex; flex-direction: column; gap: 5px; color: var(--ns-text-2); font-size: 12px; }.detect-test-form label .el-input, .detect-test-form label .el-select { width: 100%; }.full-width { grid-column: 1 / -1; }.detect-test-actions { grid-column: 1 / -1; display: flex; align-items: center; gap: 10px; margin-top: 3px; }.detect-test-actions span { color: var(--ns-text-3); font-size: 11px; }.detect-test-result { min-width: 0; min-height: 270px; padding: 12px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-sm); background: var(--ns-bg-subtle); }.detect-test-result :deep(.empty-state) { padding: 42px 16px; }
.test-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; color: var(--ns-text-2); font-size: 12px; }.test-trace { margin-bottom: 8px; padding: 10px; border: 1px solid var(--ns-border); border-left: 3px solid var(--ns-info); border-radius: var(--ns-radius-sm); background: var(--ns-surface); }.test-trace.matched { border-left-color: var(--ns-success); }.test-trace.candidate { border-left-color: var(--ns-warning); }.test-trace-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.test-trace-head > div { min-width: 0; }.test-trace-head b, .test-trace-head .mono { display: block; }.test-trace-head b { color: var(--ns-text); }.test-trace-head .mono { margin-top: 2px; color: var(--ns-text-3); font-size: 10px; }.test-trace p { margin: 5px 0 8px; color: var(--ns-text-2); font-size: 11px; }.test-condition-list { display: grid; gap: 4px; }.test-condition-head { display: grid; grid-template-columns: minmax(0, 1fr) minmax(55px, .5fr); gap: 5px; color: var(--ns-text-3); font-size: 10px; font-weight: 600; }.test-condition { display: grid; grid-template-columns: 16px minmax(0, 1fr) minmax(55px, .5fr); gap: 5px; align-items: center; color: var(--ns-text-3); font-size: 10px; }.test-condition.matched { color: var(--ns-text-2); }.condition-mark { font-weight: 700; color: var(--ns-danger); }.test-condition.matched .condition-mark { color: var(--ns-success); }.test-condition-expression { min-width: 0; }.test-condition-expression small { display: block; margin-bottom: 2px; color: var(--ns-text-3); font-size: 9px; }.test-condition .mono { overflow-wrap: anywhere; }.test-no-condition { color: var(--ns-text-3); font-size: 11px; }
.detect-editor-workspace { padding-bottom: 0; }.workspace-section-actions { display: flex; align-items: center; gap: 8px; }.detect-editor-form { display: flex; flex-direction: column; gap: 12px; }.detect-form-section { padding: 16px 0; border-top: 1px solid var(--ns-border); }.detect-form-section:first-child { border-top: 0; padding-top: 0; }.detect-form-section-title { display: flex; gap: 10px; margin-bottom: 14px; }.detect-form-section-title > span { color: var(--ns-accent-fg); font-family: var(--ns-font-mono); font-size: 11px; font-weight: 700; }.detect-form-section-title h3 { margin: 0; color: var(--ns-text); font-size: 14px; font-weight: 650; }.detect-form-section-title p { margin: 4px 0 0; }.detect-form-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px 12px; }.compact-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }.detect-form-grid :deep(.el-form-item) { margin-bottom: 0; }.detect-form-grid :deep(.el-select), .detect-form-grid :deep(.el-input) { width: 100%; }.condition-block { margin-top: 10px; padding: 11px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-sm); background: var(--ns-bg-subtle); }.condition-block-head, .condition-group-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }.condition-block-head b { color: var(--ns-text-2); font-size: 12px; }.condition-group { margin-top: 8px; padding: 9px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-sm); background: var(--ns-surface); }.condition-group-head { margin-bottom: 6px; color: var(--ns-text-3); font-size: 11px; }.condition-row { display: grid; grid-template-columns: minmax(140px, .85fr) 130px minmax(140px, 1fr) auto; gap: 6px; align-items: center; margin-bottom: 6px; }.condition-row :deep(.el-input), .condition-row :deep(.el-select) { width: 100%; }.form-hint { margin: 8px 0 0; line-height: 1.5; }.inline-hint { margin-left: 8px; }.detect-advanced-warning { display: flex; gap: 8px; align-items: baseline; margin-bottom: 12px; padding: 9px 11px; border: 1px solid color-mix(in srgb, var(--ns-warning) 30%, var(--ns-border)); border-radius: var(--ns-radius-sm); background: color-mix(in srgb, var(--ns-warning) 8%, var(--ns-surface)); font-size: 12px; }.detect-advanced-warning b { color: var(--ns-warning); }.detect-advanced-warning span { color: var(--ns-text-2); }.advanced-json { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--ns-border); }.advanced-json summary { color: var(--ns-text-2); cursor: pointer; font-size: 12px; font-weight: 600; }.advanced-json p { color: var(--ns-text-3); font-size: 11px; }.advanced-json textarea { display: block; width: 100%; box-sizing: border-box; margin: 8px 0; padding: 10px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-sm); background: var(--ns-bg-inset); color: var(--ns-text); font: 11px/1.5 var(--ns-font-mono); resize: vertical; }.lifecycle-section { padding-bottom: 18px; }.lifecycle-row { display: flex; justify-content: space-between; gap: 16px; align-items: center; }.lifecycle-row > div { flex-direction: row; align-items: center; }.form-label { color: var(--ns-text-2); font-size: 12px; }.lifecycle-toggle { white-space: nowrap; }.detect-editor-footer { position: sticky; bottom: 0; z-index: 5; background: var(--ns-surface); display: flex; justify-content: flex-end; gap: 8px; padding: 14px 0 0; border-top: 1px solid var(--ns-border); }.detect-list-section { padding-top: 2px; }.list-head { align-items: center; margin-bottom: 10px; }.toolbar-count { color: var(--ns-text-3); font-size: 12px; }.detect-table-card .el-card__body { padding: 0; }
.field-option { display: flex; flex-direction: column; gap: 2px; line-height: 1.25; }.field-option small { color: var(--ns-text-3); font-size: 10px; }
@media (max-width: 1000px) { .detect-stat-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.detect-test-grid { grid-template-columns: 1fr; }.detect-form-grid, .compact-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .detect-stat-grid, .detect-test-form, .detect-form-grid, .compact-grid { grid-template-columns: 1fr; }.full-width { grid-column: auto; }.condition-row { grid-template-columns: 1fr; }.lifecycle-row { align-items: flex-start; flex-direction: column; }.lifecycle-row > div { align-items: flex-start; flex-direction: column; }.workspace-section-head { flex-direction: column; }.workspace-section-actions { width: 100%; justify-content: space-between; } }
</style>
