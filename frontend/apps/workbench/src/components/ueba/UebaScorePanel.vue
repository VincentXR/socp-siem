<script setup lang="ts">
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
import SevBadge from '../SevBadge.vue'
import type { ScoreBreakdown } from '../../api'
import { useI18n } from '../../composables/useI18n'

type ScoreForm = { severity: string; mitre: string; tiHits: number; recentAlerts: number; assetCriticality: number }

defineProps<{
  form: ScoreForm
  result: ScoreBreakdown | null
}>()
const emit = defineEmits<{ calculate: [] }>()
const { t } = useI18n()
const severities = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']
const breakdownLabel: Record<string, string> = {
  base: 'ueba.severityBaseline', tactic: 'ueba.primaryTactic', intel: 'ueba.threatIntelHits',
  frequency: 'ueba.recentEntityAlerts', asset: 'ueba.assetCriticality',
}
function riskColor(level: string) {
  const key = String(level || 'INFO').toUpperCase()
  return { CRITICAL: 'var(--ns-danger)', HIGH: 'var(--ns-danger)', MEDIUM: 'var(--ns-warning)', LOW: 'var(--ns-info)', INFO: 'var(--ns-info)' }[key] ?? 'var(--ns-info)'
}
</script>

<template>
  <el-row :gutter="12">
    <el-col :span="10">
      <el-card shadow="never">
        <template #header>{{ t('ueba.scoreInputs') }}</template>
        <el-form label-width="120px" size="small">
          <el-form-item :label="t('ueba.severityBaseline')">
            <el-select v-model="form.severity" @change="emit('calculate')" style="width:160px">
              <el-option v-for="severity in severities" :key="severity" :label="t('severities.' + severity) || severity" :value="severity" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('ueba.attackTechnique')">
            <el-input v-model="form.mitre" placeholder="T1486" style="width:160px" @change="emit('calculate')" />
          </el-form-item>
          <el-form-item :label="t('ueba.threatIntelHits')"><el-slider v-model="form.tiHits" :min="0" :max="5" show-stops @change="emit('calculate')" /></el-form-item>
          <el-form-item :label="t('ueba.recentEntityAlerts')"><el-slider v-model="form.recentAlerts" :min="0" :max="20" @change="emit('calculate')" /></el-form-item>
          <el-form-item :label="t('ueba.assetCriticality')"><el-slider v-model="form.assetCriticality" :min="0" :max="3" show-stops @change="emit('calculate')" /></el-form-item>
        </el-form>
      </el-card>
    </el-col>
    <el-col :span="14">
      <el-card shadow="never">
        <template #header>{{ t('ueba.scoreBreakdown') }}</template>
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
        <el-empty v-else :description="t('ueba.unavailable')" />
      </el-card>
    </el-col>
  </el-row>
</template>
