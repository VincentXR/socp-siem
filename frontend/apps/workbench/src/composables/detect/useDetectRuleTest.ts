import { computed, ref, watch, type Ref } from 'vue'
import { testGasRules, type DetectionIngestEvent, type RuleCondition, type RuleSpec } from '../../api'
import { ADVANCED_TYPES, clone, type RuleEditorForm } from '../../lib/detection-editor'
import { traceRuleConditions } from '../../lib/detection-test'
import { tOr } from '../../utils/i18nLabel'
import { useI18n } from '../useI18n'
import { useLatestRequest } from '../useLatestRequest'

type TestConditionTrace = { condition: RuleCondition; matched: boolean; observed: string; scope?: string }
type RuleTestTrace = {
  id: string; name: string; type: string; state: 'MATCHED' | 'CANDIDATE' | 'NO_MATCH'
  reason: string; conditions: TestConditionTrace[]
}
type RuleTestResult = { checked: number; matched: number; candidates: number; traces: RuleTestTrace[] }

type TestOptions = {
  canManageRules: Readonly<Ref<boolean>>
  rules: Ref<RuleSpec[]>
  showRuleEditor: Ref<boolean>
  ruleEditingId: Ref<string | null>
  ruleForm: Ref<RuleEditorForm>
  loadedRevisionToken: Ref<string | undefined>
  identity: () => string
  draftSpec: () => Partial<RuleSpec>
}

/** Isolated preview receipts belong to a route session and a captured draft. */
export function useDetectRuleTest(options: TestOptions) {
  const { canManageRules, rules, showRuleEditor, ruleEditingId, ruleForm, loadedRevisionToken } = options
  const { t } = useI18n()
  const requests = useLatestRequest()
  const showTest = ref(false)
  const testInput = ref({
    source: 'auth', host: 'workbench-sample', severity: 'HIGH', message: 'Failed password for admin',
    fieldsText: '{\n  "user": "admin",\n  "attempts": 6\n}',
  })
  const testRuleId = ref('')
  const testError = ref('')
  const testing = ref(false)
  const sampleEventsText = ref('')
  const testResult = ref<RuleTestResult | null>(null)
  const testReceipt = ref<{ revision?: string; ruleId: string; fingerprint: string; at: string; result: RuleTestResult } | null>(null)
  function testFingerprint(): string { return JSON.stringify({ form: ruleForm.value, samples: sampleEventsText.value, input: testInput.value, target: testRuleId.value }) }
  const testReceiptStale = computed(() => Boolean(testReceipt.value && (testReceipt.value.fingerprint !== testFingerprint() || testReceipt.value.revision !== loadedRevisionToken.value)))
  function cancelPendingTest(): void {
    requests.cancel()
    testing.value = false
  }

  function resetPreview(): void {
    cancelPendingTest()
    testRuleId.value = ''
    testResult.value = null
    testReceipt.value = null
    testError.value = ''
  }

  function applySample(sample: DetectionIngestEvent): void {
    sampleEventsText.value = JSON.stringify([sample], null, 2)
    testInput.value = { source: sample.source || '', host: sample.host || '', severity: sample.severity || 'INFO',
      message: sample.msg || '', fieldsText: JSON.stringify(sample.fields || {}, null, 2) }
  }

  // Generation ownership handles A → B → A navigation and closing/reopening the
  // same drawer, which identity strings alone cannot distinguish.
  watch(options.identity, () => { resetPreview(); showTest.value = false }, { flush: 'sync' })
  watch(showTest, open => { if (!open) cancelPendingTest() }, { flush: 'sync' })
  watch(testRuleId, () => { cancelPendingTest(); testResult.value = null; testError.value = '' }, { flush: 'sync' })

  function testStateLabel(state: RuleTestTrace['state']): string {
    return tOr(t, 'detect.testStates.' + state, state)
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
    const request = requests.start()
    const fingerprint = testFingerprint()
    const revision = loadedRevisionToken.value
    const receiptRuleId = ruleEditingId.value || testRuleId.value || 'current-page'
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
      const selected = clone(showRuleEditor.value ? [{ ...options.draftSpec(), id: ruleEditingId.value || 'dry-run-draft' }]
        : testRuleId.value ? rules.value.filter(rule => String(rule.id) === testRuleId.value) : rules.value)
      if (!selected.length || selected.length > 20) throw new Error(t('detect.ruleTestLimit'))
      const result = await testGasRules(selected, events)
      if (!request.isCurrent()) return
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
      testReceipt.value = { revision, ruleId: receiptRuleId, fingerprint, at: new Date().toISOString(), result: testResult.value }
    } catch (error) {
      if (request.isCurrent()) testError.value = error instanceof Error ? error.message : String(error)
    } finally { if (request.isCurrent()) testing.value = false }
  }

  return {
    showTest, testInput, testRuleId, testError, testing, sampleEventsText, testResult,
    testReceipt, testReceiptStale, resetPreview, applySample, testStateLabel,
    conditionScopeLabel, runIsolatedTest,
  }
}
