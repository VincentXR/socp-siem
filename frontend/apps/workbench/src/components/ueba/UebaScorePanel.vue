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

type ScoreForm = { severity: string; mitre: string; tiHits: number; recentAlerts: number; assetCriticality: number }

defineProps<{
  form: ScoreForm
  result: ScoreBreakdown | null
}>()
const emit = defineEmits<{ calculate: [] }>()
const severities = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']
const breakdownLabel: Record<string, string> = {
  base: '严重级别基线', tactic: 'ATT&CK 战术权重', intel: '情报命中加成',
  frequency: '实体频次加成', asset: '资产重要性加成',
}
function riskColor(level: string) {
  return { CRITICAL: '#f56c6c', HIGH: '#e63946', MEDIUM: '#e6a23c', LOW: '#909399', INFO: '#909399' }[level] ?? '#909399'
}
</script>

<template>
  <el-row :gutter="12">
    <el-col :span="10">
      <el-card shadow="never">
        <template #header>输入条件</template>
        <el-form label-width="120px" size="small">
          <el-form-item label="严重级别">
            <el-select v-model="form.severity" @change="emit('calculate')" style="width:160px">
              <el-option v-for="severity in severities" :key="severity" :label="severity" :value="severity" />
            </el-select>
          </el-form-item>
          <el-form-item label="ATT&CK 技术">
            <el-input v-model="form.mitre" placeholder="如 T1486" style="width:160px" @change="emit('calculate')" />
          </el-form-item>
          <el-form-item label="情报命中数"><el-slider v-model="form.tiHits" :min="0" :max="5" show-stops @change="emit('calculate')" /></el-form-item>
          <el-form-item label="近 1h 同实体告警"><el-slider v-model="form.recentAlerts" :min="0" :max="20" @change="emit('calculate')" /></el-form-item>
          <el-form-item label="资产重要性"><el-slider v-model="form.assetCriticality" :min="0" :max="3" show-stops @change="emit('calculate')" /></el-form-item>
        </el-form>
      </el-card>
    </el-col>
    <el-col :span="14">
      <el-card shadow="never">
        <template #header>评分拆解（与检测分析侧同一口径）</template>
        <div v-if="result">
          <div style="display:flex;align-items:baseline;gap:12px;margin-bottom:16px">
            <span style="font-size:44px;font-weight:700" :style="{ color: riskColor(result.level) }">{{ result.score }}</span>
            <SevBadge :value="result.level" />
            <span style="font-size:12px;color:#909399">总分上限 100</span>
          </div>
          <div v-for="(value, key) in result.breakdown" :key="key" class="bd-row">
            <span class="bd-label">{{ breakdownLabel[key] ?? key }}</span>
            <div class="bd-bar"><div class="bd-fill" :style="{ width: Math.min(100, value) + '%', background: riskColor(result.level) }" /></div>
            <span class="bd-val">+{{ value }}</span>
          </div>
        </div>
        <el-empty v-else description="评分服务不可用" />
      </el-card>
    </el-col>
  </el-row>
</template>
