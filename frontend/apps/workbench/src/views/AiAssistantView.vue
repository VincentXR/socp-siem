<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { aiAsk, type AiResult } from '../api'

const question = ref('')
const result = ref<AiResult | null>(null)
const loading = ref(false)

const quickPrompts = [
  '如何检测 SSH 暴力破解并自动封禁？',
  'SQL 注入攻击研判建议与 DETECT 模式规则配置',
  '如何编写 SPL 语句排查异常横向移动？',
  '勒索软件攻击应急响应与 SOAR 剧本编排',
  '凭据转储 (Credential Dumping) 的检测与处置',
  'MITRE ATT&CK 战术技术覆盖率如何提升？',
]

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
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="AI 助手" description="用自然语言查询安全运营知识，辅助研判告警、生成 SPL 调查语句和制定处置建议。" />
    <el-card shadow="never" class="ai-panel">
      <div class="ai-ask-row flex gap-3 items-center">
        <el-input
          v-model="question"
          clearable
          placeholder="提问：如何检测暴力破解？如何编写 SPL 排查横向移动？"
          @keyup.enter="() => ask()"
        />
        <el-button type="primary" :loading="loading" @click="() => ask()">提问</el-button>
        <el-button v-if="result || question" @click="clear">重置</el-button>
      </div>

      <div class="ai-quick-prompts my-3 flex flex-wrap gap-2 items-center text-xs">
        <span class="text-gray-400 font-medium">推荐提示：</span>
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
        <div class="ai-result-question font-semibold text-base mb-2 text-primary">问：{{ result.question }}</div>
        <div class="ai-result-answer whitespace-pre-wrap leading-relaxed text-sm text-gray-700 dark:text-gray-200">{{ result.answer }}</div>
        <div v-if="result.suggestion" class="ai-result-suggestion mt-3 p-2.5 rounded bg-blue-50/70 dark:bg-blue-950/40 text-blue-800 dark:text-blue-200 text-xs leading-normal">
          <span class="font-semibold">💡 处置建议与联动：</span>{{ result.suggestion }}
        </div>
        <div class="ai-result-meta mt-3 text-xs text-gray-400">耗时 {{ result.elapsedMs }}ms</div>
      </div>
      <div v-else class="ai-hint text-gray-400 text-xs py-4 text-center">输入一个安全运营问题或点击上方快捷提示，智能研判结果会显示在这里。</div>
    </el-card>
  </div>
</template>
