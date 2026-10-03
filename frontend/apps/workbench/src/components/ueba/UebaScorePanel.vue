<script setup lang="ts">
import 'element-plus/es/components/empty/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import ElEmpty from 'element-plus/es/components/empty/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ActionFeedback from '../ActionFeedback.vue'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/slider/style/css.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElSlider from 'element-plus/es/components/slider/index.mjs'
import { computed } from 'vue'
import SevBadge from '../SevBadge.vue'
import type { ScoreBreakdown } from '../../api'
import { useI18n } from '../../composables/useI18n'
import { tOr } from '../../utils/i18nLabel'

type ScoreForm = { severity: string; mitre: string; tiHits: number; recentAlerts: number; assetCriticality: number }

const props = defineProps<{
  form: ScoreForm
  result: ScoreBreakdown | null
  loading?: boolean
  error?: string
  techniques?: Array<{ id: string; name: string }>
  techniquesLoading?: boolean
}>()
const emit = defineEmits<{ calculate: []; 'update:form': [value: ScoreForm] }>()
function updateField<K extends keyof ScoreForm>(key: K, value: ScoreForm[K]): void { emit('update:form', { ...props.form, [key]: value }) }
const { t } = useI18n()
const techniqueOptions = computed(() => {
  const options = [...(props.techniques ?? [])]
  const current = props.form.mitre.trim()
  if (current && !options.some(technique => technique.id === current)) options.unshift({ id: current, name: t('ueba.currentTechnique') })
  return options
})
const severities = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']
const breakdownLabel: Record<string, string> = {
  severity: 'ueba.severityBaseline', base: 'ueba.severityBaseline', tactic: 'ueba.primaryTactic', intel: 'ueba.threatIntelHits',
  frequency: 'ueba.recentEntityAlerts', asset: 'ueba.assetCriticality',
}
function riskColor(level: string) {
  const key = String(level || 'INFO').toUpperCase()
  return { CRITICAL: 'var(--ns-danger)', HIGH: 'var(--ns-danger)', MEDIUM: 'var(--ns-warning)', LOW: 'var(--ns-info)', INFO: 'var(--ns-info)' }[key] ?? 'var(--ns-info)'
}
</script>

<template>
  <el-row :gutter="12">
    <el-col :xs="24" :md="10">
      <el-card shadow="never">
        <template #header>{{ t('ueba.scoreInputs') }}</template>
        <el-form label-position="top" size="small">
          <el-form-item :label="t('ueba.severityBaseline')">
            <el-select :model-value="form.severity" @update:model-value="value => updateField('severity', String(value))" @change="emit('calculate')" style="width:160px">
              <el-option v-for="severity in severities" :key="severity" :label="tOr(t, 'severities.' + severity, severity)" :value="severity" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('ueba.attackTechnique')">
            <el-select :model-value="form.mitre" @update:model-value="value => updateField('mitre', String(value))" filterable clearable :loading="techniquesLoading" :placeholder="t('ueba.attackTechniquePlaceholder')" style="width:100%" @change="emit('calculate')">
              <el-option v-for="technique in techniqueOptions" :key="technique.id" :label="`${technique.id} · ${technique.name}`" :value="technique.id" />
            </el-select>
            <span class="field-hint">{{ techniques?.length ? t('ueba.attackTechniqueHint') : t('ueba.noTechniques') }}</span>
          </el-form-item>
          <el-form-item :label="t('ueba.threatIntelHits')"><el-slider :model-value="form.tiHits" @update:model-value="value => updateField('tiHits', Number(value))" :min="0" :max="5" show-stops @change="emit('calculate')" /></el-form-item>
          <el-form-item :label="t('ueba.recentEntityAlerts')"><el-slider :model-value="form.recentAlerts" @update:model-value="value => updateField('recentAlerts', Number(value))" :min="0" :max="20" @change="emit('calculate')" /></el-form-item>
          <el-form-item :label="t('ueba.assetCriticality')"><el-slider :model-value="form.assetCriticality" @update:model-value="value => updateField('assetCriticality', Number(value))" :min="0" :max="3" show-stops @change="emit('calculate')" /></el-form-item>
        </el-form>
      </el-card>
    </el-col>
    <el-col :xs="24" :md="14">
      <el-card shadow="never">
        <template #header>{{ t('ueba.scoreBreakdown') }}</template>
        <div v-if="loading" role="status">{{ t('common.loading') }}</div>
        <ActionFeedback :error="error" />
        <el-button v-if="error" @click="emit('calculate')">{{ t('common.retry') }}</el-button>
        <div v-if="result">
          <div style="display:flex;align-items:baseline;gap:12px;margin-bottom:16px">
            <span style="font-size:44px;font-weight:700" :style="{ color: riskColor(result.level) }">{{ result.score }}</span>
            <SevBadge :value="result.level" />
            <span style="font-size:12px;color:var(--ns-text-3)">{{ t('ueba.scoreCap') }}</span>
          </div>
          <div v-for="(value, key) in result.breakdown" :key="key" class="bd-row">
            <span class="bd-label">{{ breakdownLabel[key] ? t(breakdownLabel[key]) : key }}</span>
            <div class="bd-bar"><div class="bd-fill" :style="{ width: Math.min(100, value) + '%', background: riskColor(result.level) }" /></div>
            <span class="bd-val">+{{ value }}</span>
          </div>
        </div>
        <el-empty v-else-if="!loading && !error" :description="t('ueba.scoreAwaiting')" />
      </el-card>
    </el-col>
  </el-row>
</template>
