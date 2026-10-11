import { computed, ref, type Ref } from 'vue'
import { onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { createGasRule, getRule, updateGasRule, type DetectionIngestEvent, type RuleSpec } from '../../api'
import { ApiError } from '../../api/core'
import {
  RULE_TYPES, buildRuleSpec, checkConditions, clone, emptyCondition, emptyConditionErrors,
  emptyRuleForm, formFromRule, normalizeRuleSpec, textValue, type ConditionBlocks,
} from '../../lib/detection-editor'
import { takeDetectionSample } from '../../lib/detection-sample'
import { useI18n } from '../useI18n'
import { useLatestRequest } from '../useLatestRequest'
import { useUnsavedChanges } from '../useUnsavedChanges'

type EditorOptions = {
  canManageRules: Readonly<Ref<boolean>>
  canActivate: Readonly<Ref<boolean>>
  loadError: Ref<string>
  reloadRules: () => Promise<void>
  toggleRule: (rule: RuleSpec) => Promise<boolean>
  applySample: (sample: DetectionIngestEvent) => void
}

/** Owns one route-bound draft and its fetched revision, separate from editable JSON. */
export function useDetectRuleEditor(options: EditorOptions) {
  const { canManageRules, canActivate, loadError } = options
  const { t } = useI18n()
  const route = useRoute()
  const router = useRouter()
  const editorRequests = useLatestRequest()
  const mutationRequests = useLatestRequest()
  const editorLoading = ref(false)
  const saving = ref(false)
  const saveError = ref('')
  const saveNotice = ref('')
  const saveConflict = ref(false)
  const loadedRevisionToken = ref<string>()
  const conditionErrors = ref(emptyConditionErrors())
  const ruleFieldErrors = ref<Record<string, string>>({})
  const scopeSection = ref<HTMLElement>()
  const logicSection = ref<HTMLElement>()
  const alertSection = ref<HTMLElement>()
  const showRuleEditor = ref(false)
  const ruleEditingId = ref<string | null>(null)
  const sourceRule = ref<RuleSpec | null>(null)
  const advancedJson = ref('{}')
  const advancedError = ref('')
  const ruleForm = ref(emptyRuleForm())
  const changes = useUnsavedChanges(() => ({ form: ruleForm.value, json: advancedJson.value }), () => showRuleEditor.value, () => saving.value)
  onBeforeRouteUpdate((to, from) => to.path !== from.path || to.query.copy === from.query.copy || changes.canLeave())
  const rawOnlyRuleType = computed(() => Boolean(ruleForm.value.type) && !RULE_TYPES.includes(ruleForm.value.type))
  const publicationLink = computed(() => ruleEditingId.value ? router.resolve({ name: 'rule-edit', params: { ruleId: ruleEditingId.value } }).href : '')

  function resetFeedback(): void {
    saveError.value = ''; advancedError.value = ''; saveNotice.value = ''; saveConflict.value = false
    ruleFieldErrors.value = {}; conditionErrors.value = emptyConditionErrors()
  }

  function acceptSavedRule(rule: RuleSpec): void {
    sourceRule.value = clone(rule)
    ruleForm.value = formFromRule(rule)
    advancedJson.value = JSON.stringify(rule, null, 2)
    loadedRevisionToken.value = rule.revisionToken
    changes.markSaved()
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

  function acceptRestoredRule(rule: RuleSpec): void {
    if (ruleEditingId.value === String(rule.id)) {
      acceptSavedRule(rule)
      resetFeedback()
    }
    void options.reloadRules()
  }
  async function syncEditorRoute() {
    const request = editorRequests.start()
    mutationRequests.cancel()
    saving.value = false
    ruleEditingId.value = null
    loadedRevisionToken.value = undefined
    sourceRule.value = null
    showRuleEditor.value = false
    editorLoading.value = false
    if (!route.meta.editor) return
    const id = String(route.params.ruleId || route.query.copy || '')
    if (!canManageRules.value && (!id || Boolean(route.query.copy))) {
      await router.replace({ name: 'detect' })
      return
    }
    loadError.value = ''
    editorLoading.value = Boolean(id)
    let rule: RuleSpec | null = null
    try {
      rule = id ? normalizeRuleSpec(await getRule(id, { signal: request.signal })) : null
      if (!request.isCurrent()) return
      if (id && !rule) throw new Error(t('detect.ruleNotFound', { id }))
    } catch (failure) {
      if (request.isCurrent()) loadError.value = failure instanceof Error ? failure.message : String(failure)
      return
    } finally {
      if (request.isCurrent()) editorLoading.value = false
    }
    ruleEditingId.value = rule && !route.query.copy ? String(rule.id) : null
    loadedRevisionToken.value = ruleEditingId.value ? rule?.revisionToken : undefined
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
    resetFeedback()
    showRuleEditor.value = true
    if (typeof route.query.sample === 'string') {
      const sample = takeDetectionSample(route.query.sample)
      if (sample) {
        options.applySample(sample)
        saveNotice.value = t('workflow.sampleTransferred')
      }
    }
    changes.markSaved()
  }
  async function closeRuleEditor(): Promise<void> {
    if (saving.value) return
    await router.push({ name: 'detect', query: { ...route.query, copy: undefined } })
  }

  function focusFirstProblemSection(): void {
    const element = ruleFieldErrors.value.name ? scopeSection.value
      : Object.keys(conditionErrors.value.match).length || Object.keys(conditionErrors.value.matchAny).length || Object.keys(conditionErrors.value.steps).length ? logicSection.value
        : Object.keys(conditionErrors.value.whitelist).length ? alertSection.value
          : undefined
    if (element && typeof element.scrollIntoView === 'function') element.scrollIntoView({ block: 'center' })
  }

  /** A draft with unusable conditions cannot be dry-run either. */
  function draftConditions(): ConditionBlocks {
    const checked = validateConditions()
    if (checked.problem) throw new Error(checked.problem)
    return checked.blocks
  }
  function validateConditions() {
    const checked = checkConditions(ruleForm.value, t)
    conditionErrors.value = checked.errors
    return checked
  }

  function draftSpec(conditions = draftConditions()): Partial<RuleSpec> {
    return buildRuleSpec(ruleForm.value, sourceRule.value, ruleEditingId.value, conditions)
  }

  async function saveRule(): Promise<void> {
    if (!canManageRules.value || saving.value) return
    saveError.value = ''
    saveNotice.value = ''
    saveConflict.value = false
    ruleFieldErrors.value = {}
    if (!ruleForm.value.name.trim()) {
      ruleFieldErrors.value.name = t('forms.fieldRequired', { field: t('common.name') })
      saveError.value = t('detect.nameRequired')
      focusFirstProblemSection()
      return
    }
    const checked = validateConditions()
    if (checked.problem) {
      saveError.value = checked.problem
      focusFirstProblemSection()
      return
    }
    const request = mutationRequests.start()
    saving.value = true
    try {
      const spec = draftSpec(checked.blocks)
      const saved = ruleEditingId.value ? await updateGasRule(ruleEditingId.value, spec, loadedRevisionToken.value) : await createGasRule(spec)
      if (!request.isCurrent()) return
      const normalized = normalizeRuleSpec(saved)
      if (normalized) { ruleEditingId.value = String(normalized.id); acceptSavedRule(normalized) }
      loadedRevisionToken.value = normalized?.revisionToken
      changes.markSaved()
      await options.reloadRules()
      if (!request.isCurrent()) return
      if (checked.blank) saveNotice.value = t('detect.ignoredEmptyConditions', { count: checked.blank })
      // The write is settled and the saved form is the new baseline. Release the
      // navigation guard before moving a newly created rule to its edit route.
      saving.value = false
      if (ruleEditingId.value) await router.replace({ name: 'rule-edit', params: { ruleId: ruleEditingId.value }, query: { ...route.query, copy: undefined } })
    } catch (error) {
      if (!request.isCurrent()) return
      saveConflict.value = error instanceof ApiError && [412, 428].includes(error.status)
      saveError.value = saveConflict.value ? t('detect.concurrentEdit') : t('detect.saveFailed', { message: error instanceof Error ? error.message : String(error) })
    } finally { if (request.isCurrent()) saving.value = false }
  }

  function applyAdvancedJson(): void {
    if (!canManageRules.value || saving.value) return
    advancedError.value = ''
    try {
      const parsed = JSON.parse(advancedJson.value) as unknown
      const normalized = normalizeRuleSpec(parsed)
      if (!normalized) throw new Error(t('detect.invalidRuleJson'))
      sourceRule.value = clone(normalized); ruleForm.value = formFromRule(normalized); advancedJson.value = JSON.stringify(normalized, null, 2)
    } catch (error) { advancedError.value = error instanceof Error ? error.message : String(error) }
  }

  function downloadRuleDraft(): void {
    const url = URL.createObjectURL(new Blob([JSON.stringify(draftSpec(), null, 2)], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `${(ruleEditingId.value || 'rule').replace(/[^A-Za-z0-9._-]/g, '_')}-draft.json`
    link.click()
    URL.revokeObjectURL(url)
  }

  async function publishSavedRule(): Promise<void> {
    const id = ruleEditingId.value
    if (!id || !sourceRule.value || changes.dirty.value || !canActivate.value || saving.value) return
    const request = mutationRequests.start()
    const rule = clone(sourceRule.value)
    saving.value = true
    saveError.value = ''
    try {
      if (!await options.toggleRule(rule) || !request.isCurrent()) return
      const updated = normalizeRuleSpec(await getRule(id, { signal: request.signal }))
      if (!request.isCurrent()) return
      if (!updated) throw new Error(t('detect.ruleNotFound', { id }))
      acceptSavedRule(updated)
    } catch (error) {
      if (request.isCurrent()) saveError.value = error instanceof Error ? error.message : String(error)
    } finally {
      if (request.isCurrent()) saving.value = false
    }
  }

  function addMatchAnyGroup(): void { if (canManageRules.value) ruleForm.value.matchAny.push([emptyCondition()]) }
  function addStep(): void { if (canManageRules.value) ruleForm.value.steps.push([emptyCondition()]) }

  return {
    editorLoading, saving, saveError, saveNotice, saveConflict, loadedRevisionToken,
    conditionErrors, ruleFieldErrors, scopeSection, logicSection, alertSection,
    showRuleEditor, ruleEditingId, sourceRule, advancedJson, advancedError, ruleForm,
    changes, rawOnlyRuleType, publicationLink, onGroupByChange, acceptRestoredRule,
    syncEditorRoute, closeRuleEditor, draftSpec, saveRule, applyAdvancedJson,
    downloadRuleDraft, publishSavedRule, addMatchAnyGroup, addStep,
  }
}
