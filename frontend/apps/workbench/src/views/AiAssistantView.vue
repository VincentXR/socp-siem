<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { aiAsk, appendInvestigationToIncident, investigateAlert, type AiResult, type InvestigationResult } from '../api'
import { useI18n } from '../composables/useI18n'

const { t, locale } = useI18n()

const question = ref('')
const result = ref<AiResult | null>(null)
const loading = ref(false)
const alertId = ref('')
const investigation = ref<InvestigationResult | null>(null)
const investigationLoading = ref(false)
const appendLoading = ref(false)
const investigationError = ref('')

const quickPrompts = computed(() => locale.value === 'en-US' ? [
  'How to detect SSH brute force and auto-block attackers?',
  'SQL injection triage guidance and DETECT rules config',
  'How to write SPL queries to investigate lateral movement?',
  'Ransomware incident response and SOAR playbook orchestration',
  'Credential Dumping detection and containment procedures',
  'How to improve MITRE ATT&CK tactical coverage?',
] : [
  '如何检测 SSH 暴力破解并自动封禁？',
  'SQL 注入攻击研判建议与 DETECT 模式规则配置',
  '如何编写 SPL 语句排查异常横向移动？',
  '勒索软件攻击应急响应与 SOAR 剧本编排',
  '凭据转储 (Credential Dumping) 的检测与处置',
  'MITRE ATT&CK 战术技术覆盖率如何提升？',
])

async function ask(queryText?: string) {
  const query = (queryText || question.value).trim()
  if (!query || loading.value) return
  question.value = query
  loading.value = true
  try {
    result.value = await aiAsk(query)
  } finally {
    loading.value = false
  }
}

function clear() {
  question.value = ''
  result.value = null
}

async function investigate() {
  const id = alertId.value.trim()
  if (!id || investigationLoading.value) return
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

async function appendToIncident() {
  if (!investigation.value || appendLoading.value || investigation.value.summaryAppended) return
  appendLoading.value = true
  investigationError.value = ''
  try {
    investigation.value = await appendInvestigationToIncident(investigation.value.investigationId)
  } catch (error) {
    investigationError.value = error instanceof Error ? error.message : String(error)
  } finally {
    appendLoading.value = false
  }
}
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :title="t('ai.title')" :description="t('ai.description')" />
    <el-card shadow="never" class="ai-panel">
      <div class="ai-ask-row flex gap-3 items-center">
        <el-input
          v-model="question"
          clearable
          :placeholder="t('ai.placeholder')"
          @keyup.enter="() => ask()"
        />
        <el-button type="primary" :loading="loading" @click="() => ask()">{{ t('ai.askBtn') }}</el-button>
        <el-button v-if="result || question" @click="clear">{{ t('ai.resetBtn') }}</el-button>
      </div>

      <div class="ai-quick-prompts my-3 flex flex-wrap gap-2 items-center text-xs">
        <span class="text-gray-400 font-medium">{{ t('ai.quickPromptLabel') }}</span>
        <el-tag
          v-for="(prompt, idx) in quickPrompts"
          :key="idx"
          size="small"
          effect="plain"
          class="cursor-pointer hover:opacity-80 transition-opacity"
          @click="ask(prompt)"
        >
          {{ prompt }}
        </el-tag>
      </div>

      <div v-if="result" class="ai-result mt-4 p-4 rounded bg-gray-50 dark:bg-gray-800 border border-gray-100 dark:border-gray-700">
        <div class="ai-result-question font-semibold text-base mb-2 text-primary">{{ locale === 'en-US' ? 'Q: ' : '问：' }}{{ result.question }}</div>
        <div class="ai-result-answer whitespace-pre-wrap leading-relaxed text-sm text-gray-700 dark:text-gray-200">{{ result.answer }}</div>
        <div v-if="result.suggestion" class="ai-result-suggestion mt-3 p-2.5 rounded bg-blue-50/70 dark:bg-blue-950/40 text-blue-800 dark:text-blue-200 text-xs leading-normal">
          <span class="font-semibold">{{ t('ai.suggestionTitle') }}</span>{{ result.suggestion }}
        </div>
        <div class="ai-result-meta mt-3 flex items-center gap-2 text-xs text-gray-400">
          <el-tag size="small" effect="plain">{{ result.source }}</el-tag>
          <span>{{ t('ai.elapsed', { ms: result.elapsedMs }) }}</span>
        </div>
      </div>
      <div v-else class="ai-hint text-gray-400 text-xs py-4 text-center">{{ t('ai.hint') }}</div>
    </el-card>

    <el-card shadow="never" class="ai-panel mt-4">
      <div class="flex items-center justify-between gap-3 mb-2">
        <div>
          <h3 class="font-semibold text-base">{{ locale === 'en-US' ? 'Alert Investigation Agent' : '告警调查 Agent' }}</h3>
          <p class="text-xs text-gray-400 mt-1">{{ locale === 'en-US' ? 'Evidence-first, tenant-scoped investigation; SOAR actions always require approval.' : '从告警出发读取证据并生成可审计调查；SOAR 动作始终需要人工批准。' }}</p>
        </div>
        <el-tag size="small" type="warning" effect="plain">{{ locale === 'en-US' ? 'Human approval required' : '需人工批准' }}</el-tag>
      </div>
      <div class="ai-ask-row flex gap-3 items-center">
        <el-input v-model="alertId" clearable :placeholder="locale === 'en-US' ? 'Alert ID' : '告警 ID'" @keyup.enter="investigate" />
        <el-button type="primary" :loading="investigationLoading" @click="investigate">{{ locale === 'en-US' ? 'Investigate' : '开始调查' }}</el-button>
      </div>
      <div v-if="investigationError" class="text-xs text-red-500 mt-2">{{ investigationError }}</div>
      <div v-if="investigation" class="ai-result mt-4 p-4 rounded bg-gray-50 dark:bg-gray-800 border border-gray-100 dark:border-gray-700">
        <div class="flex items-center gap-2 mb-3">
          <el-tag size="small" :type="investigation.status === 'COMPLETED' ? 'success' : 'warning'">{{ investigation.status }}</el-tag>
          <span class="text-xs text-gray-400">{{ investigation.investigationId }}</span>
          <el-tag v-if="investigation.duplicate" size="small" effect="plain">{{ locale === 'en-US' ? 'replayed receipt' : '幂等回放' }}</el-tag>
        </div>
        <div class="text-sm leading-relaxed whitespace-pre-wrap">{{ investigation.analysis }}</div>
        <div class="mt-4">
          <div class="font-semibold text-sm mb-2">{{ locale === 'en-US' ? 'Evidence timeline' : '证据时间线' }}</div>
          <div v-for="item in investigation.timeline" :key="`${item.timestamp}-${item.citation}`" class="text-xs border-l-2 border-blue-300 pl-3 mb-2">
            <span class="text-gray-400">{{ item.timestamp }}</span> · <span class="font-medium">{{ item.type }}</span> · {{ item.message }}
            <span class="text-blue-500 ml-1">[{{ item.citation }}]</span>
          </div>
        </div>
        <div class="mt-4">
          <div class="font-semibold text-sm mb-2">{{ locale === 'en-US' ? 'Recommended SPL' : '推荐 SPL' }}</div>
          <code class="block text-xs p-2 rounded bg-gray-100 dark:bg-gray-900 whitespace-pre-wrap">{{ investigation.recommendedSpl }}</code>
        </div>
        <div v-if="investigation.hypotheses?.length" class="mt-4">
          <div class="font-semibold text-sm mb-2">{{ locale === 'en-US' ? 'Hypotheses' : '调查假设' }}</div>
          <div v-for="hypothesis in investigation.hypotheses" :key="hypothesis.hypothesis" class="text-xs mb-2">
            <span class="font-medium">{{ hypothesis.hypothesis }}</span> · {{ Math.round(hypothesis.confidence * 100) }}%
          </div>
        </div>
        <div v-if="investigation.nextActions?.length" class="mt-4">
          <div class="font-semibold text-sm mb-2">{{ locale === 'en-US' ? 'Next actions' : '下一步动作' }}</div>
          <div v-for="action in investigation.nextActions" :key="`${action.type}-${action.description}`" class="text-xs mb-2">
            <span class="font-medium">{{ action.type }}</span> · {{ action.description }}
            <span v-if="action.status" class="text-gray-400"> ({{ action.status }})</span>
          </div>
        </div>
        <div class="mt-4 flex items-center gap-2">
          <el-button type="success" plain :loading="appendLoading" :disabled="investigation.summaryAppended" @click="appendToIncident">
            {{ investigation.summaryAppended ? (locale === 'en-US' ? 'Appended to Incident' : '已写入 Incident') : (locale === 'en-US' ? 'Append summary to Incident' : '写入 Incident 时间线') }}
          </el-button>
          <span v-if="investigation.incidentId" class="text-xs text-gray-400">{{ investigation.incidentId }}</span>
        </div>
        <div v-if="investigation.citations?.length" class="mt-3 text-xs text-gray-400">
          {{ locale === 'en-US' ? 'Citations: ' : '引用：' }}{{ investigation.citations.map(citation => citation.id).join(', ') }}
        </div>
      </div>
    </el-card>
  </div>
</template>
