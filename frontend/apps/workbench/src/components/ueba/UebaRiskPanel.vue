<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/input-number/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import ElInputNumber from 'element-plus/es/components/input-number/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import type { ECharts } from 'echarts/core'
import { loadEcharts } from '../../lib/echarts'
import SevBadge from '../SevBadge.vue'
import type { RiskEntity, RiskSummary } from '../../api'

const props = defineProps<{
  theme: 'light' | 'dark'
  entities: RiskEntity[]
  summary: RiskSummary | null
  riskLimit: number
}>()

const emit = defineEmits<{
  'update:riskLimit': [value: number]
  refresh: []
  select: [entity: RiskEntity]
}>()

const riskBarEl = ref<HTMLElement>()
const chartRiskBar = shallowRef<ECharts>()
let renderToken = 0

function sevColor(severity: string) {
  return { CRITICAL: '#f56c6c', HIGH: '#e63946', MEDIUM: '#e6a23c', LOW: '#909399', INFO: '#909399' }[severity] ?? '#909399'
}

function renderRiskBar() {
  const token = ++renderToken
  setTimeout(async () => {
    const echarts = await loadEcharts()
    if (token !== renderToken || !riskBarEl.value) return
    if (!chartRiskBar.value || chartRiskBar.value.isDisposed()) chartRiskBar.value = echarts.init(riskBarEl.value, 'socp')
    const top = props.entities.slice(0, 10).slice().reverse()
    chartRiskBar.value.setOption({
      grid: { left: 4, right: 40, top: 10, bottom: 10, containLabel: true },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: { type: 'value', max: 100, axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { type: 'dashed' } } },
      yAxis: { type: 'category', data: top.map(entity => entity.entity), axisLabel: { fontSize: 11 } },
      series: [{ type: 'bar', barWidth: 14, data: top.map(entity => ({ value: entity.risk, itemStyle: { color: sevColor(entity.level), borderRadius: [0, 7, 7, 0] } })), label: { show: true, position: 'right', fontSize: 11, formatter: '{c}' } }],
    })
  }, 80)
}

function onLimitChange(value: number | undefined) {
  if (typeof value === 'number') emit('update:riskLimit', value)
  emit('refresh')
}

function onResize() { chartRiskBar.value?.resize() }

watch(() => props.theme, renderRiskBar)
watch(() => props.entities, renderRiskBar, { deep: true })
onMounted(renderRiskBar)
onMounted(() => window.addEventListener('resize', onResize))
onUnmounted(() => {
  renderToken++
  window.removeEventListener('resize', onResize)
  chartRiskBar.value?.dispose()
})
</script>

<template>
  <div>
    <div style="display:flex;gap:10px;align-items:center;margin-bottom:12px">
      <span style="font-size:13px;color:#909399">Top N</span>
      <el-input-number :model-value="riskLimit" :min="5" :max="100" :step="5" size="small" @change="onLimitChange" />
      <el-button size="small" @click="emit('refresh')">刷新</el-button>
      <span style="font-size:12px;color:#909399">
        风险分 = 严重级别基线 + ATT&amp;CK 战术权重 + 情报命中 + 频次 + 资产重要性，按 {{ summary?.halfLifeHours ?? 6 }} 小时半衰期指数衰减
      </span>
    </div>
    <el-row :gutter="12">
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>风险 Top 10</template>
          <div ref="riskBarEl" style="height:340px"></div>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>实体明细（点击行下钻）</template>
          <el-table :data="entities" size="small" border height="340" @row-click="emit('select', $event)">
            <el-table-column label="风险" width="80">
              <template #default="{ row }"><span class="risk-pill" :style="{ background: sevColor(row.level) }">{{ row.risk }}</span></template>
            </el-table-column>
            <el-table-column label="实体" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono">{{ row.entity }}</span>
                <el-tag v-if="row.critical" size="small" type="danger" effect="dark" style="margin-left:6px">核心资产</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="alerts" label="告警数" width="80" />
            <el-table-column label="最高级别" width="100">
              <template #default="{ row }"><SevBadge :value="row.maxSeverity" /></template>
            </el-table-column>
            <el-table-column label="主要战术" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">
                <el-tag v-for="m in row.mitre.slice(0, 3)" :key="m.technique" size="small" style="margin-right:4px">{{ m.technique }}×{{ m.count }}</el-tag>
                <span v-if="!row.mitre.length" style="color:#c0c4cc">—</span>
              </template>
            </el-table-column>
            <el-table-column label="最近活动" width="150" show-overflow-tooltip>
              <template #default="{ row }"><span class="mono" style="font-size:12px">{{ row.lastSeen ? new Date(row.lastSeen).toLocaleString('zh-CN', { hour12: false }) : '—' }}</span></template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>
