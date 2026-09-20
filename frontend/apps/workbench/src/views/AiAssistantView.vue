<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import { aiAsk, appendInvestigationToIncident, investigateAlert, type AiResult, type InvestigationResult } from '../api'
import { useI18n } from '../composables/useI18n'
import { tOr } from '../utils/i18nLabel'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const question = ref('')
const result = ref<AiResult | null>(null)
const loading = ref(false)
const askError = ref('')
const alertId = ref('')
const investigation = ref<InvestigationResult | null>(null)
const investigationLoading = ref(false)
const appendLoading = ref(false)
const investigationError = ref('')

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
  loading.value = true
  askError.value = ''
  try {
    result.value = await aiAsk(query)
  } catch (error) {
    askError.value = error instanceof Error ? error.message : String(error)
  } finally {
    loading.value = false
  }
}

function clear() {
  question.value = ''
  result.value = null
  askError.value = ''
}

async function investigate() {
  const id = alertId.value.trim()
  if (!id || investigationLoading.value || appendLoading.value) return
  if (route.query.alarmId !== id) void router.replace({ query: { ...route.query, alarmId: id } })
  investigationLoading.value = true
  investigationError.value = ''
  try {
    investigation.value = await investigateAlert(id)
  } catch (error) {
    investigationError.value = error instanceof Error ? error.message : String(error)
  } finally {
    investigationLoading.value = false
  }
}

function openAlarm(): void {
  if (!alertId.value.trim()) return
  void router.push({ name: 'alarms', query: { alarmId: alertId.value.trim() } })
}

async function appendToIncident() {
  if (!investigation.value || appendLoading.value || investigation.value.summaryAppended) return
  const targetInvestigationId = investigation.value.investigationId
  appendLoading.value = true
  investigationError.value = ''
  try {
    const updated = await appendInvestigationToIncident(targetInvestigationId)
    // A newer investigate() may have replaced the card while this request was
    // in flight; writing its stale result would show the previous alarm's
    // analysis under the current alarm's input.
    if (investigation.value?.investigationId === targetInvestigationId) investigation.value = updated
  } catch (error) {
    investigationError.value = error instanceof Error ? error.message : String(error)
  } finally {
    appendLoading.value = false
  }
}

onMounted(() => {
  if (!contextAlarmId.value) return
  alertId.value = contextAlarmId.value
  void investigate()
})
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :eyebrow="t('menuGroup.analyticsAndAi')" :title="t('ai.title')" :description="t('ai.description')" />
    <el-card shadow="never" class="ai-panel">
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
      </div>
      <div v-if="investigationError" class="ai-error">{{ investigationError }}</div>
      <div v-if="investigation" class="ai-result">
        <div class="ai-investigation-meta">
          <el-tag size="small" :type="investigation.status === 'COMPLETED' ? 'success' : 'warning'">{{ investigation.status }}</el-tag>
          <span class="ai-muted">{{ investigation.investigationId }}</span>
          <el-tag v-if="investigation.duplicate" size="small" effect="plain">{{ t('ai.investigation.replayedReceipt') }}</el-tag>
        </div>
        <div class="ai-analysis">{{ investigation.analysis }}</div>
        <div class="ai-section">
          <div class="ai-section-title">{{ t('ai.investigation.evidenceTimeline') }}</div>
          <div v-for="item in investigation.timeline" :key="`${item.timestamp}-${item.citation}`" class="ai-timeline-item">
            <span class="ai-muted">{{ item.timestamp }}</span> · <span class="ai-timeline-type">{{ item.type }}</span> · {{ item.message }}
            <span class="ai-citation">[{{ item.citation }}]</span>
          </div>
        </div>
        <div class="ai-section">
          <div class="ai-section-title">{{ t('ai.investigation.recommendedSpl') }}</div>
          <code class="ai-result-code">{{ investigation.recommendedSpl }}</code>
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
          </div>
        </div>
        <div class="ai-append-row">
          <el-button type="success" plain :loading="appendLoading" :disabled="investigation.summaryAppended" @click="appendToIncident">
            {{ investigation.summaryAppended ? t('ai.investigation.appendedToIncident') : t('ai.investigation.appendSummaryToIncident') }}
          </el-button>
          <span v-if="investigation.incidentId" class="ai-muted">{{ investigation.incidentId }}</span>
        </div>
        <div v-if="investigation.citations?.length" class="ai-citations ai-muted">
          {{ t('ai.investigation.citations') }}{{ investigation.citations.map(citation => citation.id).join(', ') }}
        </div>
      </div>
    </el-card>
  </div>
</template>
