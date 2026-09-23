<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/empty/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElEmpty from 'element-plus/es/components/empty/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, onUnmounted } from 'vue'
import ActionFeedback from '../components/ActionFeedback.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import { complianceCoverage, complianceFrameworks, lookupRuleOptions } from '../api'
import { useRequest } from '../composables/useRequest'
import { useI18n } from '../composables/useI18n'

const { t, locale } = useI18n()

type Framework = { name: string; controls: Array<{ id: string; name: string; ruleIds: string[] }> }
type Coverage = {
  byFramework: Array<{ framework: string; controls: Array<{ id: string; name: string; covered: boolean; mappedRules: string[]; assessment?: string; owner?: string; validUntil?: string; evidence?: Record<string, unknown> }>; coverage: number }>
  totalControls: number
  coveredControls: number
  coverage: number
  contentVersion?: string
  generatedAt?: string
}

const request = useRequest<{ frameworks: Framework[]; rules: Awaited<ReturnType<typeof lookupRuleOptions>>; coverage: Coverage }>()
const coverage = computed(() => request.data.value?.coverage ?? null)
const rules = computed(() => request.data.value?.rules ?? [])
const loading = request.loading
const loadError = computed(() => request.error.value?.message ?? '')
let disposed = false
const activeRuleIds = computed(() => new Set(rules.value.filter(rule => String(rule.status || '').toUpperCase() === 'ACTIVE').map(rule => String(rule.id ?? '')).filter(Boolean)))
const controls = computed(() => coverage.value?.byFramework.flatMap(framework => framework.controls) ?? [])
const mappedControls = computed(() => controls.value.filter(control => control.mappedRules.length > 0).length)
const enabledControls = computed(() => controls.value.filter(control => control.mappedRules.some(ruleId => activeRuleIds.value.has(ruleId))).length)
const verifiedControls = computed(() => controls.value.filter(control => ['verified', 'validated'].includes(String(control.assessment || '').toLowerCase())).length)

type CoverageControl = Coverage['byFramework'][number]['controls'][number]
function controlState(control: Partial<CoverageControl>) {
  const mappedRules = control.mappedRules ?? []
  return {
    mapped: mappedRules.length > 0,
    enabled: mappedRules.some(ruleId => activeRuleIds.value.has(ruleId)),
    verified: ['verified', 'validated'].includes(String(control.assessment || '').toLowerCase()),
  }
}

function formatTime(value?: string): string {
  if (!value) return t('time.notAvailable')
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? t('time.notAvailable') : date.toLocaleString(locale.value)
}

async function loadCompliance(refreshFrameworks = true) {
  if (disposed || loading.value) return
  await request.execute(async signal => {
    const frameworks = refreshFrameworks || !request.data.value
      ? (await complianceFrameworks({ signal })).frameworks : request.data.value.frameworks
    signal.throwIfAborted()
    const mappedIds = [...new Set(frameworks.flatMap(framework => framework.controls.flatMap(control => control.ruleIds)))]
    const ruleResult: Awaited<ReturnType<typeof lookupRuleOptions>> = []
    for (let index = 0; index < mappedIds.length; index += 100) {
      ruleResult.push(...await lookupRuleOptions(mappedIds.slice(index, index + 100), { signal }))
      signal.throwIfAborted()
    }
    const ruleIds = ruleResult.map(rule => String(rule.id ?? '')).filter(Boolean)
    const result = await complianceCoverage(ruleIds, { signal })
    signal.throwIfAborted()
    return { frameworks, rules: ruleResult, coverage: result }
  })
}

function compute() { return loadCompliance(false) }
onMounted(() => loadCompliance())
onUnmounted(() => { disposed = true; request.cancel() })
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :title="t('compliance.mappingTitle')" :description="t('compliance.mappingDescription')">
      <template #actions><el-button size="small" :loading="loading" @click="loadCompliance()">{{ t('common.refresh') }}</el-button><el-button size="small" :loading="loading" :disabled="!coverage" @click="compute">{{ t('compliance.recalculate') }}</el-button></template>
    </PageHeader>
    <ActionFeedback :error="loadError" />
    <el-alert v-if="loadError && coverage" :title="t('compliance.staleResult')" type="warning" :closable="false" style="margin-bottom:14px" />
    <el-alert :title="t('compliance.assessmentNotice')" type="info" :closable="false" show-icon style="margin-bottom:14px" />

    <div class="page-metrics compliance-metrics">
      <MetricCard :label="t('compliance.mappingCoverage')" tone="info">{{ coverage?.coverage ?? t('time.notAvailable') }}<span v-if="coverage" class="metric-suffix">%</span></MetricCard>
      <MetricCard :label="t('compliance.mappedControls')" tone="success">{{ coverage ? mappedControls : t('time.notAvailable') }}</MetricCard>
      <MetricCard :label="t('compliance.enabledControls')" tone="warning">{{ coverage ? enabledControls : t('time.notAvailable') }}</MetricCard>
      <MetricCard :label="t('compliance.verifiedControls')" tone="neutral">{{ coverage ? verifiedControls : t('time.notAvailable') }}</MetricCard>
    </div>

    <el-card v-for="framework in (coverage?.byFramework ?? [])" :key="framework.framework" shadow="never" class="compliance-card">
      <div class="compliance-card-head"><strong>{{ framework.framework }}</strong><span>{{ t('compliance.mappingCoverage') }} {{ framework.coverage }}%</span></div>
      <el-table :data="framework.controls" size="small" border>
        <el-table-column prop="id" :label="t('compliance.control')" width="120" />
        <el-table-column prop="name" :label="t('compliance.name')" min-width="200" show-overflow-tooltip />
        <el-table-column :label="t('compliance.assessmentStatus')" min-width="250"><template #default="{ row }"><div class="compliance-statuses"><el-tag :type="controlState(row).mapped ? 'success' : 'info'" size="small">{{ controlState(row).mapped ? t('compliance.mapped') : t('compliance.unmapped') }}</el-tag><el-tag :type="controlState(row).enabled ? 'success' : 'warning'" size="small">{{ controlState(row).enabled ? t('compliance.enabled') : t('compliance.notEnabled') }}</el-tag><el-tag :type="controlState(row).verified ? 'success' : 'info'" size="small">{{ controlState(row).verified ? t('compliance.verified') : t('compliance.notVerified') }}</el-tag></div></template></el-table-column>
        <el-table-column prop="mappedRules" :label="t('compliance.mappedRules')" min-width="160" show-overflow-tooltip><template #default="{ row }"><span class="table-text compliance-rules">{{ (row.mappedRules || []).join(', ') || t('time.notAvailable') }}</span></template></el-table-column>
        <el-table-column prop="validUntil" :label="t('compliance.validUntil')" width="155"><template #default="{ row }">{{ formatTime(row.validUntil) }}</template></el-table-column>
      </el-table>
    </el-card>
    <el-empty v-if="!loading && !loadError && !coverage?.byFramework?.length" :description="t('compliance.noData')" />
    <div v-if="coverage" class="compliance-footnote">{{ t('compliance.contentVersion', { value: coverage.contentVersion || t('time.notAvailable') }) }} · {{ t('compliance.generatedAt', { value: formatTime(coverage.generatedAt) }) }}</div>
  </div>
</template>
