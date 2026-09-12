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
import { computed, onMounted, ref } from 'vue'
import ActionFeedback from '../components/ActionFeedback.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import { complianceCoverage, complianceFrameworks, listRules } from '../api'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()

type Framework = { name: string; controls: Array<{ id: string; name: string; ruleIds: string[] }> }
type Coverage = {
  byFramework: Array<{ framework: string; controls: Array<{ id: string; name: string; covered: boolean; mappedRules: string[]; assessment?: string; owner?: string; validUntil?: string; evidence?: Record<string, unknown> }>; coverage: number }>
  totalControls: number
  coveredControls: number
  coverage: number
  contentVersion?: string
  generatedAt?: string
}

const frameworks = ref<Framework[]>([])
const rules = ref<Array<{ id?: string; enabled?: boolean; status?: string }>>([])
const coverage = ref<Coverage | null>(null)
const loading = ref(false)
const loadError = ref('')
const activeRuleIds = computed(() => new Set(rules.value.filter(rule => rule.enabled || String(rule.status || '').toUpperCase() === 'ACTIVE').map(rule => String(rule.id ?? '')).filter(Boolean)))
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
  try { return new Date(value).toLocaleString() } catch { return value }
}

async function compute() {
  if (loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    const ruleResult = await listRules()
    const ruleIds = ruleResult.map(rule => String(rule.id ?? '')).filter(Boolean)
    // Keep the lifecycle calculation local to the actual rule catalogue; the coverage endpoint only reports mappings.
    rules.value = ruleResult
    coverage.value = await complianceCoverage(ruleIds)
  } catch (failure) {
    loadError.value = failure instanceof Error ? failure.message : String(failure)
  } finally { loading.value = false }
}

async function loadCompliance() {
  if (loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    const result = await complianceFrameworks()
    frameworks.value = result.frameworks
  } catch (failure) {
    loadError.value = failure instanceof Error ? failure.message : String(failure)
  } finally {
    loading.value = false
  }
  await compute()
}

onMounted(loadCompliance)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :title="t('compliance.mappingTitle')" :description="t('compliance.mappingDescription')">
      <template #actions><el-button size="small" :loading="loading" @click="loadCompliance">{{ t('common.refresh') }}</el-button><el-button size="small" :loading="loading" @click="compute">{{ t('compliance.recalculate') }}</el-button></template>
    </PageHeader>
    <ActionFeedback :error="loadError" />
    <el-alert :title="t('compliance.assessmentNotice')" type="info" :closable="false" show-icon style="margin-bottom:14px" />

    <div class="page-metrics compliance-metrics">
      <MetricCard :label="t('compliance.mappingCoverage')" tone="info">{{ coverage?.coverage ?? t('time.notAvailable') }}<span class="metric-suffix">%</span></MetricCard>
      <MetricCard :label="t('compliance.mappedControls')" tone="success">{{ mappedControls }}</MetricCard>
      <MetricCard :label="t('compliance.enabledControls')" tone="warning">{{ enabledControls }}</MetricCard>
      <MetricCard :label="t('compliance.verifiedControls')" tone="neutral">{{ verifiedControls }}</MetricCard>
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
    <el-empty v-if="!coverage?.byFramework?.length" :description="t('compliance.noData')" />
    <div v-if="coverage" class="compliance-footnote">{{ t('compliance.contentVersion', { value: coverage.contentVersion || t('time.notAvailable') }) }} · {{ t('compliance.generatedAt', { value: formatTime(coverage.generatedAt) }) }}</div>
  </div>
</template>
