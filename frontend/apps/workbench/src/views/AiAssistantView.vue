<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onScopeDispose, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import { getAlarm, getAlarmEvidence, getCase, splSearch, tiMatch, aiAsk, appendInvestigationToIncident, investigateAlert, reanalyzeAlert, type AiResult, type InvestigationResult, type InvestigationCitation } from '../api'
import { useI18n } from '../composables/useI18n'
import { useLatestRequest } from '../composables/useLatestRequest'
import { tOr } from '../utils/i18nLabel'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const investigationReturn = computed(() => typeof route.query.returnTo === 'string' && /^\/(alarms|cases)(?:[?#]|$)/.test(route.query.returnTo) ? route.query.returnTo : '')

const question = ref('')
const result = ref<AiResult | null>(null)
const loading = ref(false)
const askError = ref('')
const askRequest = useLatestRequest()
const alertId = ref('')
const investigation = ref<InvestigationResult | null>(null)
const investigationLoading = ref(false)
const appendTarget = ref<string | null>(null)
const appendLoading = computed(() => appendTarget.value !== null)
let investigationVersion = 0
let disposed = false
onScopeDispose(() => { disposed = true })
const investigationError = ref('')
const jobId = ref(typeof route.query.jobId === 'string' ? route.query.jobId : '')
const selectedCitation = ref<InvestigationCitation | null>(null)
const citationEvidence = ref<unknown>(null)
const citationError = ref('')
const citationLoading = ref(false)
const citationRequest = useLatestRequest()
const genericVisible = ref(false)
const caseId = computed(() => typeof route.query.caseId === 'string' ? route.query.caseId : '')
function rememberJob(id: string): void {
  if (disposed) return
  jobId.value = id
  void router.replace({ query: { ...route.query, jobId: id } })
}
function openSuggestedSearch(q: string): void {
  if (q.trim()) void router.push({ name: 'search', query: { draft: q, range: 'all', alarmId: alertId.value, caseId: caseId.value || undefined, returnTo: route.fullPath } })
}
function openCase(id: string): void { void router.push({ name: 'case', query: { caseId: id } }) }
function openResponse(): void { void router.push({ name: 'soar', query: { tab: 'runs', alarmId: alertId.value, caseId: caseId.value || undefined, returnTo: route.fullPath } }) }
async function inspectCitation(id: string): Promise<void> {
  const citation = investigation.value?.citations.find(item => item.id === id)
  selectedCitation.value = citation ?? { id, source: 'unavailable', label: t('analystJourney.missingCitationLabel') }
  citationEvidence.value = null; citationError.value = ''; citationLoading.value = false
  const request = citationRequest.start()
  if (!citation) { citationError.value = t('analystJourney.missingCitation'); return }
  citationLoading.value = true
  try {
    const separator = id.indexOf(':')
    const kind = id.slice(0, separator), target = id.slice(separator + 1)
    // Resolve only known same-origin API contracts. Never follow generated locators as URLs.
    let evidence: unknown
    if (kind === 'alert') evidence = await getAlarm(target, { signal: request.signal })
    else if (kind === 'evidence') evidence = (await getAlarmEvidence(alertId.value, { signal: request.signal })).items.find(item => item.eventId === target)
    else if (kind === 'search') evidence = (await splSearch(`eventId=${JSON.stringify(target)}`, { limit: 10, signal: request.signal })).events
    else if (kind === 'incident') evidence = await getCase(target, { signal: request.signal })
    else if (kind === 'ioc') evidence = await tiMatch(target, { signal: request.signal })
    else evidence = citation.value
    if (!request.isCurrent()) return
    if (evidence === undefined || evidence === null) citationError.value = t('analystJourney.unavailableEvidence')
    else citationEvidence.value = evidence
  } catch (failure) { if (request.isCurrent()) citationError.value = failure instanceof Error ? failure.message : String(failure) }
  finally { if (request.isCurrent()) citationLoading.value = false }
}
function closeCitation(): void { citationRequest.cancel(); selectedCitation.value = null; citationEvidence.value = null; citationLoading.value = false }

const investigationRequest = useLatestRequest()

const contextAlarmId = computed(() => {
  if (typeof route.query.alarmId === 'string') return route.query.alarmId
  // Keep links created by older sessions usable while the canonical URL key is alarmId.
  return typeof route.query.alertId === 'string' ? route.query.alertId : ''
})

const quickPrompts = computed(() => [
  t('ai.quickPromptBruteForce'),
  t('ai.quickPromptSqlInjection'),
  t('ai.quickPromptLateralMovement'),
  t('ai.quickPromptRansomware'),
  t('ai.quickPromptCredentialDumping'),
  t('ai.quickPromptMitreCoverage'),
])

async function ask(queryText?: string) {
  const query = (queryText || question.value).trim()
  if (!query || loading.value) return
  question.value = query
  const request = askRequest.start()
  loading.value = true
  askError.value = ''
  try {
    const response = await aiAsk(query, { signal: request.signal })
    if (request.isCurrent()) result.value = response
  } catch (error) {
    if (request.isCurrent()) askError.value = error instanceof Error ? error.message : String(error)
  } finally {
    if (request.isCurrent()) loading.value = false
  }
}

function citationRoute(id: string) {
  const [kind, ...parts] = id.split(':')
  const value = parts.join(':')
  if (!value) return undefined
  if (kind === 'alert') return { name: 'alarms', query: { alarmId: value } }
  if (kind === 'incident') return { name: 'case', query: { caseId: value } }
  if (kind === 'search' || kind === 'evidence') return { name: 'search', query: { q: `eventId=${JSON.stringify(value)}`, range: 'all' } }
  return undefined
}

function clear() {
  askRequest.cancel()
  loading.value = false
  question.value = ''
  result.value = null
  askError.value = ''
}

function resetInvestigation() {
  closeCitation()
  investigationVersion++
  investigationRequest.cancel()
  investigationLoading.value = false
  investigation.value = null
  investigationError.value = ''
}

watch(() => alertId.value.trim(), resetInvestigation, { flush: 'sync' })

async function loadInvestigation(id: string) {
  if (disposed || investigationLoading.value) return
  resetInvestigation()
  investigationLoading.value = true
  const request = investigationRequest.start()
  try {
    const response = await investigateAlert(id, { signal: request.signal, jobId: jobId.value || undefined, onJob: receipt => { if (request.isCurrent()) rememberJob(receipt) } })
    if (!request.isCurrent()) return
    if (response.alertId !== id) throw new Error(t('ai.investigation.contextMismatch'))
    investigation.value = response
  } catch (error) {
    if (request.isCurrent()) investigationError.value = error instanceof Error ? error.message : String(error)
  } finally {
    if (request.isCurrent()) investigationLoading.value = false
  }
}

async function investigate() {
  const id = alertId.value.trim()
  if (!id || investigationLoading.value) return
  if (contextAlarmId.value === id) { await loadInvestigation(id); return }
  const version = investigationVersion
  try {
    // The route watcher owns loading after navigation, including Back/Forward.
    const failure = await router.replace({ query: { ...route.query, alarmId: id, alertId: undefined, jobId: undefined } })
    if (failure) throw failure
  } catch (error) {
    if (!disposed && version === investigationVersion)
      investigationError.value = error instanceof Error ? error.message : String(error)
  }
}

async function reanalyze() {
  const id = alertId.value.trim()
  const baseRevision = investigation.value?.revision
  if (!id || !baseRevision || investigationLoading.value) return
  resetInvestigation()
  investigationLoading.value = true
  const request = investigationRequest.start()
  try {
    const response = await reanalyzeAlert(id, baseRevision, { signal: request.signal, onJob: receipt => { if (request.isCurrent()) rememberJob(receipt) } })
    if (!request.isCurrent()) return
    if (response.alertId !== id) throw new Error(t('ai.investigation.contextMismatch'))
    investigation.value = response
  } catch (error) {
    if (request.isCurrent()) investigationError.value = error instanceof Error ? error.message : String(error)
  } finally {
    if (request.isCurrent()) investigationLoading.value = false
  }
}

function openAlarm(): void {
  if (!contextAlarmId.value.trim()) return
  void router.push({ name: 'alarms', query: { alarmId: contextAlarmId.value.trim() } })
}

async function appendToIncident() {
  const current = investigation.value
  if (!current || appendLoading.value || investigationLoading.value || current.summaryAppended
    || current.alertId !== alertId.value.trim()) return
  const version = investigationVersion
  const target = current.investigationId
  appendTarget.value = target
  investigationError.value = ''
  const ownsView = () => !disposed && version === investigationVersion && investigation.value?.investigationId === target
  try {
    const updated = await appendInvestigationToIncident(target, caseId.value || undefined)
    if (!ownsView()) return
    if (updated.investigationId !== target || updated.alertId !== current.alertId)
      throw new Error(t('ai.investigation.contextMismatch'))
    investigation.value = updated
  } catch (error) {
    if (ownsView()) investigationError.value = error instanceof Error ? error.message : String(error)
  } finally {
    if (!disposed) appendTarget.value = null
  }
}

// The alert and resumable job are one navigation identity. Independent watchers
// can each submit when navigation changes the alert and clears its previous job.
watch([contextAlarmId, () => route.query.jobId], ([id, routeJobId], [previousId]) => {
  const nextJobId = typeof routeJobId === 'string' ? routeJobId : ''
  // Remembering this request's receipt changes the URL without restarting it.
  if (id === previousId && nextJobId === jobId.value) return
  alertId.value = id
  jobId.value = nextJobId
  selectedCitation.value = null
  resetInvestigation()
  if (id.trim()) void loadInvestigation(id.trim())
}, { immediate: true })
</script>

<template>
  <div class="page-pad view-enter" :class="{ 'ai-context-layout': contextAlarmId }">
    <el-button v-if="investigationReturn" @click="router.push(investigationReturn)">{{ t('analystJourney.returnToInvestigation') }}</el-button>
    <PageHeader :eyebrow="t('menuGroup.analyticsAndAi')" :title="t('ai.title')" :description="t('ai.description')" />
    <el-button v-if="contextAlarmId" @click="genericVisible = !genericVisible">{{ t('analystJourney.generalQuestions') }}</el-button>
    <el-card v-if="!contextAlarmId || genericVisible" shadow="never" class="ai-panel">
      <div class="ai-ask-row">
        <el-input
          v-model="question"
          clearable
          :placeholder="t('ai.placeholder')"
          @keyup.enter="() => ask()"
        />
        <el-button type="primary" :loading="loading" @click="() => ask()">{{ t('ai.askBtn') }}</el-button>
        <el-button v-if="result || question" @click="clear">{{ t('ai.resetBtn') }}</el-button>
      </div>

      <div v-if="askError" class="ai-error" role="alert">
        <span>{{ askError }}</span>
        <el-button link type="primary" size="small" @click="ask()">{{ t('common.retry') }}</el-button>
      </div>

      <div class="ai-quick-prompts">
        <span class="ai-quick-label">{{ t('ai.quickPromptLabel') }}</span>
        <el-tag
          v-for="(prompt, idx) in quickPrompts"
          :key="idx"
          size="small"
          effect="plain"
          class="ai-quick-tag"
          role="button"
          tabindex="0"
          :aria-label="prompt"
          @click="ask(prompt)"
          @keydown.enter.space.prevent="ask(prompt)"
        >
          {{ prompt }}
        </el-tag>
      </div>

      <div v-if="result" class="ai-result">
        <div class="ai-result-question">{{ t('ai.investigation.questionPrefix') }}{{ result.question }}</div>
        <div class="ai-result-answer">{{ result.answer }}</div>
        <div v-if="result.suggestion" class="ai-result-suggestion">
          <span class="ai-emphasis">{{ t('ai.suggestionTitle') }}</span>{{ result.suggestion }}
        </div>
        <div class="ai-result-meta">
          <el-tag size="small" effect="plain">{{ tOr(t, 'ai.sources.' + result.source.toLowerCase(), result.source) }}</el-tag>
          <span>{{ t('ai.elapsed', { ms: result.elapsedMs }) }}</span>
        </div>
      </div>
      <div v-else class="ai-hint">{{ t('ai.hint') }}</div>
    </el-card>

    <el-card shadow="never" class="ai-panel ai-investigation-panel">
      <div class="ai-investigation-head">
        <div>
          <h3 class="ai-investigation-title">{{ t('ai.investigation.agentTitle') }}</h3>
          <p class="ai-muted ai-investigation-description">{{ t('ai.investigation.evidenceFirstDescription') }}</p>
        </div>
        <el-tag size="small" type="warning" effect="plain">{{ t('ai.investigation.approvalRequired') }}</el-tag>
      </div>
      <div v-if="contextAlarmId" class="ai-context-banner">
        <span>{{ t('ai.investigation.contextFromAlarm') }} <code>{{ contextAlarmId }}</code></span>
        <el-button link type="primary" size="small" @click="openAlarm">{{ t('ai.investigation.openAlarm') }}</el-button>
      </div>
      <div class="ai-ask-row">
        <el-input v-model="alertId" clearable :placeholder="t('ai.investigation.alertId')" @keyup.enter="investigate" />
        <el-button type="primary" :loading="investigationLoading" @click="investigate">{{ t('ai.investigation.investigate') }}</el-button>
        <el-button v-if="investigation" :disabled="investigationLoading" @click="reanalyze">{{ t('ai.investigation.reanalyze') }}</el-button>
      </div>
      <div v-if="appendLoading && appendTarget !== investigation?.investigationId" role="status" class="ai-muted">{{ t('ai.investigation.previousAppendPending') }}</div>
      <div v-if="jobId" class="ai-context-banner"><span>{{ t('analystJourney.resumableJob', { id: jobId }) }} </span><el-button :disabled="investigationLoading" @click="loadInvestigation(alertId)">{{ t('analystJourney.resumeJob') }}</el-button></div>
      <div v-if="investigationError" role="alert" class="ai-error">{{ investigationError }}</div>
      <div v-if="investigation" class="ai-result">
        <div class="ai-investigation-meta">
          <el-tag size="small" :type="investigation.status === 'COMPLETED' ? 'success' : 'warning'">{{ tOr(t, 'workflow.' + investigation.status, investigation.status) }}</el-tag>
          <span class="ai-muted">{{ investigation.investigationId }}</span>
          <el-tag size="small" effect="plain">{{ t('ai.investigation.revision', { revision: investigation.revision }) }}</el-tag>
          <el-tag v-if="investigation.duplicate" size="small" effect="plain">{{ t('ai.investigation.replayedReceipt') }}</el-tag>
        </div>
        <p v-if="investigation.degradedSources?.length" role="alert" class="workflow-guide">{{ t('workflow.aiDegraded', { sources: investigation.degradedSources.join(', ') }) }}</p>
        <div class="ai-analysis">{{ investigation.analysis }}</div>
        <div class="ai-section">
          <div class="ai-section-title">{{ t('ai.investigation.evidenceTimeline') }}</div>
          <div v-for="item in investigation.timeline" :key="`${item.timestamp}-${item.citation}`" class="ai-timeline-item">
            <span class="ai-muted">{{ item.timestamp }}</span> · <span class="ai-timeline-type">{{ item.type }}</span> · {{ item.message }}
            <el-button link @click="inspectCitation(item.citation)">[{{ item.citation }}]</el-button>
          </div>
        </div>
        <div class="ai-section">
          <div class="ai-section-title">{{ t('ai.investigation.recommendedSpl') }}</div>
          <code class="ai-result-code">{{ investigation.recommendedSpl }}</code>
          <el-button :disabled="!investigation.recommendedSpl" @click="openSuggestedSearch(investigation.recommendedSpl)">{{ t('analystJourney.reviewQuery') }}</el-button>
        </div>
        <div v-if="investigation.hypotheses?.length" class="ai-section">
          <div class="ai-section-title">{{ t('ai.investigation.hypotheses') }}</div>
          <div v-for="hypothesis in investigation.hypotheses" :key="hypothesis.hypothesis" class="ai-list-item">
            <span class="ai-item-type">{{ hypothesis.hypothesis }}</span> · {{ Math.round(hypothesis.confidence * 100) }}%
          </div>
        </div>
        <div v-if="investigation.nextActions?.length" class="ai-section">
          <div class="ai-section-title">{{ t('ai.investigation.nextActions') }}</div>
          <div v-for="action in investigation.nextActions" :key="`${action.type}-${action.description}`" class="ai-list-item">
            <span class="ai-item-type">{{ action.type }}</span> · {{ action.description }}
            <span v-if="action.status" class="ai-muted"> ({{ action.status }})</span>
            <el-button v-if="action.query" link @click="openSuggestedSearch(action.query)">{{ t('analystJourney.reviewSearch') }}</el-button>
            <el-button v-if="/soar|response|action/i.test(action.type)" link @click="openResponse">{{ t('analystJourney.reviewResponse') }}</el-button>
          </div>
        </div>
        <div class="ai-append-row">
          <el-button type="success" plain :loading="appendTarget === investigation.investigationId" :disabled="appendLoading || investigation.summaryAppended" @click="appendToIncident">
            {{ investigation.summaryAppended ? t('ai.investigation.appendedToIncident') : t('ai.investigation.appendSummaryToIncident') }}
          </el-button>
          <el-button v-if="investigation.incidentId" link @click="openCase(investigation.incidentId)">{{ t('drawer.goToCase') }} · {{ investigation.incidentId }}</el-button>
        </div>
        <div v-if="investigation.citations?.length" class="ai-citations ai-muted">
          {{ t('ai.investigation.citations') }}
          <div v-for="citation in investigation.citations" :key="citation.id">
            <router-link v-if="citationRoute(citation.id)" :to="citationRoute(citation.id)!">{{ citation.description || citation.label || citation.id }}</router-link>
            <span v-else>{{ citation.description || citation.label || citation.id }}</span>
            <el-button link @click="inspectCitation(citation.id)">{{ t('analystJourney.citationEvidence') }}</el-button>
            <small> · {{ citation.id }} · {{ citation.source }}</small>
          </div>
        </div>
      </div>
      <section v-if="selectedCitation" class="ai-result" :aria-label="t('analystJourney.citationEvidence')"><h4>{{ selectedCitation.label || selectedCitation.description || selectedCitation.id }}</h4><p>{{ selectedCitation.source }} · {{ selectedCitation.id }}</p><p v-if="citationLoading" role="status">{{ t('analystJourney.loadingEvidence') }}</p><p v-if="citationError" role="alert">{{ citationError }}</p><pre v-if="citationEvidence">{{ JSON.stringify(citationEvidence, null, 2) }}</pre><el-button @click="closeCitation">{{ t('analystJourney.closeEvidence') }}</el-button></section>
      <p>{{ t('analystJourney.generatedGuidance') }}</p>
    </el-card>
  </div>
</template>

<style scoped>
.ai-context-layout { display: flex; flex-direction: column; }
.ai-context-layout > .ai-panel { order: 2; }
.ai-context-layout > .ai-investigation-panel { order: 1; }
</style>
