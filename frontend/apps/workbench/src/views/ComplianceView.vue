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
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()

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
    const rules = await listRules()
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
    <PageHeader :title="t('compliance.title')" :description="t('compliance.description')">
      <template #actions><el-button size="small" :loading="loading" @click="compute">{{ t('compliance.recalculate') }}</el-button></template>
    </PageHeader>

    <div class="page-metrics compliance-metrics">
      <MetricCard :label="t('compliance.overallCoverage')" tone="info">{{ coverage?.coverage ?? t('time.notAvailable') }}<span class="metric-suffix">%</span></MetricCard>
      <MetricCard :label="t('compliance.coveredControls')" tone="success">{{ coverage?.coveredControls ?? t('time.notAvailable') }}</MetricCard>
      <MetricCard :label="t('compliance.totalControls')" tone="neutral">{{ coverage?.totalControls ?? t('time.notAvailable') }}</MetricCard>
    </div>

    <el-card v-for="framework in (coverage?.byFramework ?? [])" :key="framework.framework" shadow="never" class="compliance-card">
      <div class="compliance-card-head"><strong>{{ framework.framework }}</strong><span>{{ framework.coverage }}%</span></div>
      <el-table :data="framework.controls" size="small" border>
        <el-table-column prop="id" :label="t('compliance.control')" width="120" />
        <el-table-column prop="name" :label="t('compliance.name')" min-width="200" show-overflow-tooltip />
        <el-table-column :label="t('compliance.coverage')" width="90"><template #default="{ row }"><el-tag :type="row.covered ? 'success' : 'danger'" size="small">{{ row.covered ? t('compliance.covered') : t('compliance.missing') }}</el-tag></template></el-table-column>
        <el-table-column prop="mappedRules" :label="t('compliance.mappedRules')" min-width="160" show-overflow-tooltip><template #default="{ row }"><span class="table-text compliance-rules">{{ (row.mappedRules || []).join(', ') || t('time.notAvailable') }}</span></template></el-table-column>
      </el-table>
    </el-card>
    <el-empty v-if="!coverage?.byFramework?.length" :description="t('compliance.noData')" />
  </div>
</template>
