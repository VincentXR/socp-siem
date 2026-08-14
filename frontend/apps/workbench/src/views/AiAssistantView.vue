<script setup lang="ts">
import { ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { aiAsk, type AiResult } from '../api'

const question = ref('')
const result = ref<AiResult | null>(null)
const loading = ref(false)

async function ask() {
  if (!question.value.trim() || loading.value) return
  loading.value = true
  try {
    result.value = await aiAsk(question.value.trim())
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="AI 助手" description="用自然语言查询安全运营知识，辅助研判告警和制定处置建议。" />
    <el-card shadow="never" class="ai-panel">
      <div class="ai-ask-row">
        <el-input v-model="question" placeholder="提问：如何检测暴力破解？端口扫描怎么处理？" @keyup.enter="ask" />
        <el-button type="primary" :loading="loading" @click="ask">提问</el-button>
      </div>
      <div v-if="result" class="ai-result">
        <p class="ai-result-question">问：{{ result.question }}</p>
        <p class="ai-result-answer">{{ result.answer }}</p>
        <p v-if="result.suggestion" class="ai-result-suggestion">{{ result.suggestion }}</p>
        <p class="ai-result-meta">耗时 {{ result.elapsedMs }}ms</p>
      </div>
      <div v-else class="ai-hint">输入一个安全运营问题，结果会显示在这里。</div>
    </el-card>
  </div>
</template>
