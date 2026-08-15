<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/empty/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElEmpty from 'element-plus/es/components/empty/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { onMounted, ref } from 'vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import { complianceCoverage, complianceFrameworks, listRules } from '../api'

type Framework = { name: string; controls: Array<{ id: string; name: string; ruleIds: string[] }> }
type Coverage = {
  byFramework: Array<{ framework: string; controls: Array<{ id: string; name: string; covered: boolean; mappedRules: string[] }>; coverage: number }>
  totalControls: number
  coveredControls: number
  coverage: number
}

const frameworks = ref<Framework[]>([])
const coverage = ref<Coverage | null>(null)
const loading = ref(false)
async function compute() {
  if (loading.value) return
  loading.value = true
  try {
    const rules = await listRules() as Array<Record<string, unknown>>
    const ruleIds = rules.map(rule => String(rule.id ?? '')).filter(Boolean)
    coverage.value = await complianceCoverage(ruleIds)
  } finally { loading.value = false }
}

async function loadCompliance() {
  const result = await complianceFrameworks()
  frameworks.value = result.frameworks
  await compute()
}

onMounted(loadCompliance)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="合规" description="将检测规则映射到控制项，快速查看各框架的覆盖情况。">
      <template #actions><el-button size="small" :loading="loading" @click="compute">重新计算</el-button></template>
    </PageHeader>

    <div class="page-metrics compliance-metrics">
      <MetricCard label="整体控制项覆盖率" tone="info">{{ coverage?.coverage ?? '—' }}<span class="metric-suffix">%</span></MetricCard>
      <MetricCard label="已覆盖控制项" tone="success">{{ coverage?.coveredControls ?? '—' }}</MetricCard>
      <MetricCard label="总控制项" tone="neutral">{{ coverage?.totalControls ?? '—' }}</MetricCard>
    </div>

    <el-card v-for="framework in (coverage?.byFramework ?? [])" :key="framework.framework" shadow="never" class="compliance-card">
      <div class="compliance-card-head"><strong>{{ framework.framework }}</strong><span>{{ framework.coverage }}%</span></div>
      <el-table :data="framework.controls" size="small">
        <el-table-column prop="id" label="控制项" width="120" />
        <el-table-column prop="name" label="名称" min-width="200" />
        <el-table-column label="覆盖" width="90"><template #default="{ row }"><el-tag :type="row.covered ? 'success' : 'danger'" size="small">{{ row.covered ? '已覆盖' : '缺失' }}</el-tag></template></el-table-column>
        <el-table-column prop="mappedRules" label="映射规则" min-width="160"><template #default="{ row }"><span class="compliance-rules">{{ (row.mappedRules || []).join(', ') || '—' }}</span></template></el-table-column>
      </el-table>
    </el-card>
    <el-empty v-if="!coverage?.byFramework?.length" description="暂无合规覆盖数据" />
  </div>
</template>
